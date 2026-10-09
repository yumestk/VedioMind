package com.example.server.service;

import com.example.server.entity.AnalysisJob;
import com.example.server.entity.AnalysisJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.TranscriptSegment;
import com.example.server.entity.VideoChapter;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.ai.AudioExtractor;
import com.example.server.service.ai.ChapterGenerator;
import com.example.server.service.ai.ContentSummarizer;
import com.example.server.service.ai.SpeechTranscriber;
import com.example.server.service.ai.TranscriptionResult;
import com.example.server.utils.MinioUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AiService {

    private final MediaFileMapper mediaFileMapper;
    private final AudioExtractor audioExtractor;
    private final SpeechTranscriber speechTranscriber;
    private final ContentSummarizer contentSummarizer;
    private final ChapterGenerator chapterGenerator;
    private final AnalysisJobService analysisJobService;
    private final TranscriptService transcriptService;
    private final ChapterService chapterService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MinioUtils minioUtils;

    public AiService(
            MediaFileMapper mediaFileMapper,
            AudioExtractor audioExtractor,
            SpeechTranscriber speechTranscriber,
            ContentSummarizer contentSummarizer,
            ChapterGenerator chapterGenerator,
            AnalysisJobService analysisJobService,
            TranscriptService transcriptService,
            ChapterService chapterService,
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            MinioUtils minioUtils
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.audioExtractor = audioExtractor;
        this.speechTranscriber = speechTranscriber;
        this.contentSummarizer = contentSummarizer;
        this.chapterGenerator = chapterGenerator;
        this.analysisJobService = analysisJobService;
        this.transcriptService = transcriptService;
        this.chapterService = chapterService;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.minioUtils = minioUtils;
    }

    public void processAnalysisJob(String jobId, int retryCount) {
        RLock lock = redissonClient.getLock("lock:analysis-job:" + jobId);
        boolean locked = false;
        File audioFile = null;

        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked || !analysisJobService.beginAttempt(jobId, retryCount)) {
                return;
            }

            AnalysisJob job = analysisJobService.getRequired(jobId);
            MediaFile mediaFile = mediaFileMapper.selectById(job.getMediaId());
            if (mediaFile == null) {
                throw new IllegalArgumentException("Media does not exist: " + job.getMediaId());
            }

            List<TranscriptSegment> segments = transcriptService.listByMediaId(mediaFile.getId());
            if (segments.isEmpty()) {
                analysisJobService.markStage(jobId, AnalysisJobStatus.EXTRACTING_AUDIO, 20);
                audioFile = audioExtractor.extract(minioUtils.createReadUrl(mediaFile.getObjectKey()));

                analysisJobService.markStage(jobId, AnalysisJobStatus.TRANSCRIBING, 45);
                TranscriptionResult transcription = speechTranscriber.transcribe(audioFile);
                segments = transcriptService.replace(mediaFile.getId(), transcription.segments());
            }

            String transcript = transcriptService.joinText(segments);
            if (transcript.isBlank()) {
                throw new IllegalStateException("Speech recognition returned an empty transcript");
            }
            analysisJobService.markStage(jobId, AnalysisJobStatus.SUMMARIZING, 70);
            String summary = mediaFile.getAiSummary();
            if (summary == null || summary.isBlank()) {
                summary = contentSummarizer.summarize(transcript);
                if (summary == null || summary.isBlank()) {
                    throw new IllegalStateException("The language model returned an empty summary");
                }
                mediaFile.setAiSummary(summary);
                mediaFileMapper.updateById(mediaFile);
            }

            List<VideoChapter> chapters = chapterService.listByMediaId(mediaFile.getId());
            if (chapters.isEmpty()) {
                analysisJobService.markStage(jobId, AnalysisJobStatus.GENERATING_CHAPTERS, 90);
                chapterService.replace(mediaFile.getId(), chapterGenerator.generate(segments));
            }

            analysisJobService.markSucceeded(jobId);
            invalidateMediaList(mediaFile.getUserId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Analysis job was interrupted", exception);
        } finally {
            if (audioFile != null && audioFile.exists()) {
                audioFile.delete();
            }
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void invalidateMediaList(Long userId) {
        String userIdValue = userId == null ? "anon" : String.valueOf(userId);
        try {
            redisTemplate.delete(MediaService.MEDIA_LIST_CACHE_PREFIX + userIdValue);
        } catch (Exception ignored) {
            // Cache invalidation must not turn a completed AI job into a retry.
        }
    }
}

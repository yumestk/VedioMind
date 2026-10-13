package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final long VISUAL_EVIDENCE_TIMEOUT_MINUTES = 55;
    private static final String VISUAL_EVIDENCE_WARNING =
            "画面证据处理未完成，字幕总结、章节与问答仍可使用。";

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
    private final ObjectStorageService objectStorageService;
    private final VisualEvidenceService visualEvidenceService;
    private final VideoKnowledgeIndexService knowledgeIndexService;

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
            ObjectStorageService objectStorageService,
            VisualEvidenceService visualEvidenceService,
            VideoKnowledgeIndexService knowledgeIndexService
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
        this.objectStorageService = objectStorageService;
        this.visualEvidenceService = visualEvidenceService;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    public void processAnalysisJob(String jobId, int retryCount) {
        RLock lock = redissonClient.getLock("lock:analysis-job:" + jobId);
        boolean locked = false;
        File audioFile = null;
        Future<Void> visualEvidence = CompletableFuture.completedFuture(null);
        boolean visualEvidenceSettled = true;

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

            String videoUrl = null;
            if (isLocalStudyMedia(mediaFile)) {
                videoUrl = objectStorageService.createReadUrl(mediaFile.getObjectKey());
                try {
                    visualEvidence = visualEvidenceService.processAsync(mediaFile.getId(), videoUrl);
                } catch (RuntimeException exception) {
                    log.warn("Unable to queue visual evidence for media {}: {}",
                            mediaFile.getId(), exception.getMessage());
                    visualEvidence = CompletableFuture.failedFuture(exception);
                }
                visualEvidenceSettled = false;
            }

            List<TranscriptSegment> segments = transcriptService.listByMediaId(mediaFile.getId());
            if (segments.isEmpty()) {
                analysisJobService.markStage(jobId, AnalysisJobStatus.EXTRACTING_AUDIO, 20);
                if (videoUrl == null) {
                    videoUrl = objectStorageService.createReadUrl(mediaFile.getObjectKey());
                }
                audioFile = audioExtractor.extract(videoUrl);

                analysisJobService.markStage(jobId, AnalysisJobStatus.TRANSCRIBING, 40);
                TranscriptionResult transcription = speechTranscriber.transcribe(audioFile);
                segments = transcriptService.replace(
                        mediaFile.getId(), transcription.segments(), "ALIYUN_ASR"
                );
            }

            if (!visualEvidence.isDone()) {
                analysisJobService.markStage(jobId, AnalysisJobStatus.PROCESSING_VISUAL_EVIDENCE, 60);
            }
            String visualWarning = awaitVisualEvidence(visualEvidence, mediaFile.getId());
            visualEvidenceSettled = true;

            String transcript = transcriptService.joinText(segments);
            if (transcript.isBlank()) {
                throw new IllegalStateException("Speech recognition returned an empty transcript");
            }
            VideoKnowledgeIndexService.IndexSnapshot knowledgeIndex = null;
            if (isLocalStudyMedia(mediaFile)) {
                analysisJobService.markStage(
                        jobId, AnalysisJobStatus.BUILDING_KNOWLEDGE_INDEX, 70
                );
                knowledgeIndex = knowledgeIndexService.build(mediaFile.getId());
            }

            analysisJobService.markStage(
                    jobId,
                    AnalysisJobStatus.SUMMARIZING,
                    knowledgeIndex == null ? 75 : 85
            );
            String summary = mediaFile.getAiSummary();
            String summaryContextVersion = knowledgeIndex == null
                    ? null : knowledgeIndex.context().version();
            boolean summaryIsCurrent = summary != null && !summary.isBlank()
                    && (summaryContextVersion == null
                    || summaryContextVersion.equals(mediaFile.getAiSummaryContextVersion()));
            if (!summaryIsCurrent) {
                String summaryInput = knowledgeIndex == null
                        ? transcript : knowledgeIndex.summaryMaterial();
                summary = contentSummarizer.summarize(summaryInput);
                if (summary == null || summary.isBlank()) {
                    throw new IllegalStateException("The language model returned an empty summary");
                }
                UpdateWrapper<MediaFile> update = new UpdateWrapper<MediaFile>()
                        .eq("id", mediaFile.getId())
                        .set("ai_summary", summary);
                if (summaryContextVersion != null) {
                    update.set("ai_summary_context_version", summaryContextVersion);
                }
                mediaFileMapper.update(null, update);
            }

            List<VideoChapter> chapters = chapterService.listByMediaId(mediaFile.getId());
            if (chapters.isEmpty()) {
                analysisJobService.markStage(jobId, AnalysisJobStatus.GENERATING_CHAPTERS, 95);
                chapterService.replace(mediaFile.getId(), chapterGenerator.generate(segments));
            }

            analysisJobService.markSucceeded(jobId, visualWarning);
            invalidateMediaList(mediaFile.getUserId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Analysis job was interrupted", exception);
        } catch (RuntimeException exception) {
            if (!visualEvidenceSettled) {
                try {
                    awaitVisualEvidence(visualEvidence, null);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    exception.addSuppressed(interrupted);
                }
            }
            throw exception;
        } finally {
            if (!visualEvidence.isDone()) {
                visualEvidence.cancel(true);
            }
            if (audioFile != null && audioFile.exists()) {
                audioFile.delete();
            }
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String awaitVisualEvidence(Future<Void> visualEvidence, Long mediaId)
            throws InterruptedException {
        try {
            visualEvidence.get(VISUAL_EVIDENCE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            return null;
        } catch (ExecutionException | CancellationException exception) {
            Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                    ? exception.getCause()
                    : exception;
            log.warn("Visual evidence processing failed{}: {}",
                    mediaId == null ? "" : " for media " + mediaId,
                    cause.getMessage());
            return VISUAL_EVIDENCE_WARNING;
        } catch (TimeoutException exception) {
            visualEvidence.cancel(true);
            log.warn("Visual evidence processing timed out{}",
                    mediaId == null ? "" : " for media " + mediaId);
            return VISUAL_EVIDENCE_WARNING;
        }
    }

    private boolean isLocalStudyMedia(MediaFile mediaFile) {
        return "LOCAL_UPLOAD".equals(mediaFile.getSourceType())
                && "STUDY".equals(mediaFile.getAnalysisMode());
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

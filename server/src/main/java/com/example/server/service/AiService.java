package com.example.server.service;

import com.example.server.entity.AnalysisJob;
import com.example.server.entity.AnalysisJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.MinioUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.TimeUnit;

@Service
public class AiService {

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    private final AnalysisJobService analysisJobService;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MinioUtils minioUtils;

    public AiService(
            MediaFileMapper mediaFileMapper,
            @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
            AnalysisJobService analysisJobService,
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            MinioUtils minioUtils
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.analysisJobService = analysisJobService;
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

            String transcript = mediaFile.getTranscriptText();
            if (transcript == null || transcript.isBlank()) {
                analysisJobService.markStage(jobId, AnalysisJobStatus.EXTRACTING_AUDIO, 20);
                audioFile = aiAnalysisStrategy.extractAudio(minioUtils.createReadUrl(mediaFile.getObjectKey()));

                analysisJobService.markStage(jobId, AnalysisJobStatus.TRANSCRIBING, 45);
                transcript = aiAnalysisStrategy.transcribe(audioFile);
                if (transcript == null || transcript.isBlank()) {
                    throw new IllegalStateException("Speech recognition returned an empty transcript");
                }
                mediaFile.setTranscriptText(transcript);
                mediaFileMapper.updateById(mediaFile);
            }

            analysisJobService.markStage(jobId, AnalysisJobStatus.SUMMARIZING, 75);
            String summary = mediaFile.getAiSummary();
            if (summary == null || summary.isBlank()) {
                summary = aiAnalysisStrategy.generateSummary(transcript);
                if (summary == null || summary.isBlank()) {
                    throw new IllegalStateException("The language model returned an empty summary");
                }
                mediaFile.setAiSummary(summary);
                mediaFileMapper.updateById(mediaFile);
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

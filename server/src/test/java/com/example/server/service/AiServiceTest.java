package com.example.server.service;

import com.example.server.entity.AnalysisJob;
import com.example.server.entity.AnalysisJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final AiAnalysisStrategy strategy = mock(AiAnalysisStrategy.class);
    private final AnalysisJobService analysisJobService = mock(AnalysisJobService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);

    private AiService aiService;

    @BeforeEach
    void setUp() throws Exception {
        aiService = new AiService(
                mediaFileMapper,
                strategy,
                analysisJobService,
                redisTemplate,
                redissonClient
        );
        when(redissonClient.getLock("lock:analysis-job:job-1")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(analysisJobService.beginAttempt("job-1", 0)).thenReturn(true);

        AnalysisJob job = new AnalysisJob();
        job.setId("job-1");
        job.setMediaId(42L);
        when(analysisJobService.getRequired("job-1")).thenReturn(job);
    }

    @Test
    void transcribesOnceAndSummarizesThePersistedTranscript() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setFilePath("video.mp4");
        File audioFile = mock(File.class);

        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(strategy.extractAudio("video.mp4")).thenReturn(audioFile);
        when(strategy.transcribe(audioFile)).thenReturn("the transcript");
        when(strategy.generateSummary("the transcript")).thenReturn("the summary");

        aiService.processAnalysisJob("job-1", 0);

        verify(strategy).extractAudio("video.mp4");
        verify(strategy).transcribe(audioFile);
        verify(strategy).generateSummary("the transcript");
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.TRANSCRIBING, 45);
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.SUMMARIZING, 75);
        verify(analysisJobService).markSucceeded("job-1");
        verify(redisTemplate).delete("media:list:user:7");
    }

    @Test
    void reusesAnExistingTranscriptInsteadOfCallingAsrAgain() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setFilePath("video.mp4");
        media.setTranscriptText("saved transcript");

        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(strategy.generateSummary("saved transcript")).thenReturn("the summary");

        aiService.processAnalysisJob("job-1", 0);

        verify(strategy, never()).extractAudio(any());
        verify(strategy, never()).transcribe(any());
        verify(strategy).generateSummary("saved transcript");
        verify(analysisJobService).markSucceeded("job-1");
    }
}

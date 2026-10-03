package com.example.server.service;

import com.example.server.entity.AnalysisJob;
import com.example.server.entity.AnalysisJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.TranscriptSegment;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.ai.AudioExtractor;
import com.example.server.service.ai.ContentSummarizer;
import com.example.server.service.ai.SpeechTranscriber;
import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.service.ai.TranscriptionResult;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final AudioExtractor audioExtractor = mock(AudioExtractor.class);
    private final SpeechTranscriber speechTranscriber = mock(SpeechTranscriber.class);
    private final ContentSummarizer contentSummarizer = mock(ContentSummarizer.class);
    private final AnalysisJobService analysisJobService = mock(AnalysisJobService.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final RLock lock = mock(RLock.class);

    private AiService aiService;

    @BeforeEach
    void setUp() throws Exception {
        aiService = new AiService(
                mediaFileMapper,
                audioExtractor,
                speechTranscriber,
                contentSummarizer,
                analysisJobService,
                transcriptService,
                redisTemplate,
                redissonClient,
                minioUtils
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
    void transcribesOncePersistsSegmentsAndSummarizesTheirText() {
        MediaFile media = media();
        File audioFile = mock(File.class);
        TranscriptionResult result = new TranscriptionResult(List.of(
                new TranscriptSegmentDraft(0, 1_500, "first sentence"),
                new TranscriptSegmentDraft(1_500, 3_000, "second sentence")
        ));
        List<TranscriptSegment> persisted = List.of(
                segment(1L, 0, 0, 1_500, "first sentence"),
                segment(2L, 1, 1_500, 3_000, "second sentence")
        );

        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(List.of());
        when(minioUtils.createReadUrl("video.mp4")).thenReturn("https://media/video.mp4");
        when(audioExtractor.extract("https://media/video.mp4")).thenReturn(audioFile);
        when(speechTranscriber.transcribe(audioFile)).thenReturn(result);
        when(transcriptService.replace(42L, result.segments())).thenReturn(persisted);
        when(transcriptService.joinText(persisted)).thenReturn("first sentence\nsecond sentence");
        when(contentSummarizer.summarize("first sentence\nsecond sentence")).thenReturn("the summary");

        aiService.processAnalysisJob("job-1", 0);

        verify(audioExtractor).extract("https://media/video.mp4");
        verify(speechTranscriber).transcribe(audioFile);
        verify(transcriptService).replace(42L, result.segments());
        verify(contentSummarizer).summarize("first sentence\nsecond sentence");
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.TRANSCRIBING, 45);
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.SUMMARIZING, 75);
        verify(analysisJobService).markSucceeded("job-1");
        verify(redisTemplate).delete("media:list:v2:user:7");
    }

    @Test
    void reusesPersistedSegmentsInsteadOfCallingAsrAgain() {
        MediaFile media = media();
        List<TranscriptSegment> persisted = List.of(
                segment(1L, 0, 0, 1_500, "saved transcript")
        );

        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(persisted);
        when(transcriptService.joinText(persisted)).thenReturn("saved transcript");
        when(contentSummarizer.summarize("saved transcript")).thenReturn("the summary");

        aiService.processAnalysisJob("job-1", 0);

        verify(audioExtractor, never()).extract(any());
        verify(speechTranscriber, never()).transcribe(any());
        verify(transcriptService, never()).replace(any(), any());
        verify(contentSummarizer).summarize("saved transcript");
        verify(analysisJobService).markSucceeded("job-1");
    }

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setObjectKey("video.mp4");
        return media;
    }

    private TranscriptSegment segment(
            Long id,
            int index,
            long startMs,
            long endMs,
            String text
    ) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(id);
        segment.setMediaId(42L);
        segment.setSegmentIndex(index);
        segment.setStartMs(startMs);
        segment.setEndMs(endMs);
        segment.setText(text);
        return segment;
    }
}

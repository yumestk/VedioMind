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
import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.service.ai.TranscriptionResult;
import com.example.server.service.ai.VideoChapterDraft;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private final ChapterGenerator chapterGenerator = mock(ChapterGenerator.class);
    private final AnalysisJobService analysisJobService = mock(AnalysisJobService.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final ChapterService chapterService = mock(ChapterService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
    private final VisualEvidenceService visualEvidenceService = mock(VisualEvidenceService.class);
    private final VideoKnowledgeIndexService knowledgeIndexService = mock(VideoKnowledgeIndexService.class);

    private AiService aiService;

    @BeforeEach
    void setUp() throws Exception {
        aiService = new AiService(
                mediaFileMapper,
                audioExtractor,
                speechTranscriber,
                contentSummarizer,
                chapterGenerator,
                analysisJobService,
                transcriptService,
                chapterService,
                redisTemplate,
                objectStorageService,
                visualEvidenceService,
                knowledgeIndexService
        );
        when(analysisJobService.beginAttempt("job-1", 0)).thenReturn(true);

        AnalysisJob job = new AnalysisJob();
        job.setId("job-1");
        job.setMediaId(42L);
        when(analysisJobService.getRequired("job-1")).thenReturn(job);
        when(visualEvidenceService.processAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(knowledgeIndexService.build(42L)).thenReturn(indexSnapshot());
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
        when(objectStorageService.createReadUrl("video.mp4")).thenReturn("https://media/video.mp4");
        when(audioExtractor.extract("https://media/video.mp4")).thenReturn(audioFile);
        when(speechTranscriber.transcribe(audioFile)).thenReturn(result);
        when(transcriptService.replace(42L, result.segments(), "ALIYUN_ASR")).thenReturn(persisted);
        when(transcriptService.joinText(persisted)).thenReturn("first sentence\nsecond sentence");
        when(contentSummarizer.summarize("indexed summaries")).thenReturn("the summary");
        List<VideoChapterDraft> chapterDrafts = List.of(
                new VideoChapterDraft("First chapter", 0, 3_000)
        );
        when(chapterGenerator.generate(persisted)).thenReturn(chapterDrafts);

        aiService.processAnalysisJob("job-1", 0);

        verify(audioExtractor).extract("https://media/video.mp4");
        verify(visualEvidenceService).processAsync(42L, "https://media/video.mp4");
        verify(speechTranscriber).transcribe(audioFile);
        verify(transcriptService).replace(42L, result.segments(), "ALIYUN_ASR");
        verify(knowledgeIndexService).build(42L);
        verify(contentSummarizer).summarize("indexed summaries");
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.TRANSCRIBING, 40);
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.BUILDING_KNOWLEDGE_INDEX, 70);
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.SUMMARIZING, 85);
        verify(analysisJobService).markStage("job-1", AnalysisJobStatus.GENERATING_CHAPTERS, 95);
        verify(chapterService).replace(42L, chapterDrafts);
        verify(analysisJobService).markSucceeded("job-1", null);
        verify(redisTemplate).delete("media:list:v3:user:7");
    }

    @Test
    void reusesPersistedTranscriptSummaryAndChapters() {
        MediaFile media = media();
        media.setAiSummary("saved summary");
        media.setAiSummaryContextVersion("t1-v1-p2");
        List<TranscriptSegment> persisted = List.of(
                segment(1L, 0, 0, 1_500, "saved transcript")
        );

        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(persisted);
        when(transcriptService.joinText(persisted)).thenReturn("saved transcript");
        when(chapterService.listByMediaId(42L)).thenReturn(List.of(new VideoChapter()));

        aiService.processAnalysisJob("job-1", 0);

        verify(audioExtractor, never()).extract(any());
        verify(speechTranscriber, never()).transcribe(any());
        verify(transcriptService, never()).replace(any(), any(), any());
        verify(contentSummarizer, never()).summarize(any());
        verify(chapterGenerator, never()).generate(any());
        verify(analysisJobService).markSucceeded("job-1", null);
    }

    @Test
    void externalMediaKeepsTheTranscriptOnlyAnalysisPath() {
        MediaFile media = media();
        media.setSourceType("EXTERNAL_URL");
        media.setAnalysisMode("QUICK_INSIGHT");
        media.setObjectKey(null);
        media.setAiSummary("saved summary");
        List<TranscriptSegment> persisted = List.of(
                segment(1L, 0, 0, 1_500, "platform transcript")
        );

        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(persisted);
        when(transcriptService.joinText(persisted)).thenReturn("platform transcript");
        when(chapterService.listByMediaId(42L)).thenReturn(List.of(new VideoChapter()));

        aiService.processAnalysisJob("job-1", 0);

        verify(visualEvidenceService, never()).processAsync(any(), any());
        verify(knowledgeIndexService, never()).build(any());
        verify(objectStorageService, never()).createReadUrl(any());
        verify(analysisJobService).markSucceeded("job-1", null);
    }

    @Test
    void visualEvidenceFailureDoesNotBlockTranscriptResults() {
        MediaFile media = media();
        media.setAiSummary("saved summary");
        media.setAiSummaryContextVersion("t1-v1-p2");
        List<TranscriptSegment> persisted = List.of(
                segment(1L, 0, 0, 1_500, "saved transcript")
        );
        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(persisted);
        when(transcriptService.joinText(persisted)).thenReturn("saved transcript");
        when(chapterService.listByMediaId(42L)).thenReturn(List.of(new VideoChapter()));
        when(visualEvidenceService.processAsync(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("OCR unavailable")));

        aiService.processAnalysisJob("job-1", 0);

        verify(analysisJobService).markSucceeded(
                "job-1",
                "画面证据处理未完成，字幕总结、章节与问答仍可使用。"
        );
    }

    @Test
    void quotaWaitCancelsUnfinishedVisualWorkWithoutWaitingForIt() {
        MediaFile media = media();
        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(List.of());
        when(objectStorageService.createReadUrl(any())).thenReturn("https://example.test/video");
        File audio = mock(File.class);
        when(audioExtractor.extract(any())).thenReturn(audio);
        var runningVisual = new java.util.concurrent.FutureTask<Void>(() -> null);
        when(visualEvidenceService.processAsync(any(), any())).thenReturn(runningVisual);
        var limited = new com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException("busy", 60);
        when(speechTranscriber.transcribe(audio)).thenThrow(limited);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> aiService.processAnalysisJob("job-1", 0))
                .isSameAs(limited);

        org.assertj.core.api.Assertions.assertThat(runningVisual.isCancelled()).isTrue();
        verify(analysisJobService, never()).markSucceeded(any(), any());
    }

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setUserId(7L);
        media.setObjectKey("video.mp4");
        media.setSourceType("LOCAL_UPLOAD");
        media.setAnalysisMode("STUDY");
        return media;
    }

    private VideoKnowledgeIndexService.IndexSnapshot indexSnapshot() {
        VideoContext context = new VideoContext(
                42L,
                "t1-v1-p2",
                "ALIYUN_ASR",
                new VideoContext.Modality("AVAILABLE", 1, 1, 1, null),
                new VideoContext.Modality("AVAILABLE", 1, 0, 0, null),
                List.of(new VideoContext.TranscriptEvidence(1L, 0, 1_500, "first sentence")),
                List.of()
        );
        return new VideoKnowledgeIndexService.IndexSnapshot(context, List.of(), "indexed summaries");
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

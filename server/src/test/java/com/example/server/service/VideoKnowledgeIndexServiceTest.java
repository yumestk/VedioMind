package com.example.server.service;

import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.mapper.VideoKnowledgeChunkMapper;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.context.VideoContext;
import com.example.server.service.context.VideoContextService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoKnowledgeIndexServiceTest {

    private final VideoKnowledgeChunkMapper chunkMapper = mock(VideoKnowledgeChunkMapper.class);
    private final VideoContextService contextService = mock(VideoContextService.class);
    private final VideoChunkSummarizer summarizer = mock(VideoChunkSummarizer.class);
    private final AliyunTextEmbeddingClient embeddingClient = mock(AliyunTextEmbeddingClient.class);
    private final QdrantKnowledgeIndexService qdrantIndexService = mock(QdrantKnowledgeIndexService.class);
    private final VideoKnowledgeIndexService service = new VideoKnowledgeIndexService(
            chunkMapper, contextService, summarizer, embeddingClient, qdrantIndexService
    );

    @Test
    void assignsBoundaryEvidenceToTheNextFiveMinuteChunk() {
        VideoContext context = context(
                "t1-v1-p2",
                List.of(
                        transcript(1L, 299_999, "first"),
                        transcript(2L, 300_000, "second")
                ),
                List.of(visual(10L, 300_000, "slide"))
        );

        List<VideoChunkSummarizer.ChunkInput> plans = service.plans(context);

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).chunkIndex()).isZero();
        assertThat(plans.get(0).transcripts()).extracting(VideoContext.TranscriptEvidence::id)
                .containsExactly(1L);
        assertThat(plans.get(1).chunkIndex()).isEqualTo(1);
        assertThat(plans.get(1).transcripts()).extracting(VideoContext.TranscriptEvidence::id)
                .containsExactly(2L);
        assertThat(plans.get(1).visuals()).extracting(VideoContext.VisualEvidence::id)
                .containsExactly(10L);
    }

    @Test
    void persistsEachSummaryAndReusesReadyChunksOnRetry() {
        List<VideoKnowledgeChunk> rows = mapperBackedByMemory();
        when(contextService.build(42L)).thenReturn(context(
                "t1-v1-p2",
                List.of(transcript(1L, 0, "first")),
                List.of()
        ));
        when(summarizer.summarize(any())).thenReturn(
                new VideoChunkSummarizer.ChunkSummary("chunk summary", List.of("topic"))
        );
        when(embeddingClient.embedDocuments(any())).thenReturn(new AliyunTextEmbeddingClient.EmbeddingBatch(
                List.of(vector(1)),
                12
        ));

        VideoKnowledgeIndexService.IndexSnapshot first = service.build(42L);
        VideoKnowledgeIndexService.IndexSnapshot second = service.build(42L);

        assertThat(first.chunks()).hasSize(1);
        assertThat(first.chunks().get(0).getStatus()).isEqualTo("READY");
        assertThat(first.summaryMaterial()).contains("chunk summary", "topic");
        assertThat(second.chunks()).hasSize(1);
        verify(summarizer, times(1)).summarize(any());
        verify(embeddingClient, times(1)).embedDocuments(any());
        verify(chunkMapper, times(1)).insert(any(VideoKnowledgeChunk.class));
        verify(qdrantIndexService, times(2)).replaceMediaIndex(any());
        assertThat(rows).hasSize(1);
    }

    @Test
    void resumesEmbeddingWithoutRegeneratingASavedSummary() {
        VideoKnowledgeChunk summarized = chunk("t1-v1-p2", 0, "SUMMARIZED");
        summarized.setId(1L);
        summarized.setSummary("saved summary");
        summarized.setKeywordsJson("[\"saved\"]");
        List<VideoKnowledgeChunk> rows = mapperBackedByMemory(summarized);
        when(contextService.build(42L)).thenReturn(context(
                "t1-v1-p2",
                List.of(transcript(1L, 0, "first")),
                List.of()
        ));
        when(embeddingClient.embedDocuments(any())).thenReturn(new AliyunTextEmbeddingClient.EmbeddingBatch(
                List.of(vector(1)),
                8
        ));

        VideoKnowledgeIndexService.IndexSnapshot result = service.build(42L);

        assertThat(result.chunks()).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("READY");
        verify(summarizer, never()).summarize(any());
        verify(embeddingClient).embedDocuments(any());
    }

    @Test
    void doesNotDeleteOldVersionsBeforeTheCurrentIndexIsReady() {
        mapperBackedByMemory();
        when(contextService.build(42L)).thenReturn(context(
                "t2-v1-p2",
                List.of(transcript(1L, 0, "first")),
                List.of()
        ));
        when(summarizer.summarize(any())).thenReturn(
                new VideoChunkSummarizer.ChunkSummary("summary", List.of("topic"))
        );
        when(embeddingClient.embedDocuments(any())).thenThrow(
                new IllegalStateException("embedding unavailable")
        );

        assertThatThrownBy(() -> service.build(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding unavailable");
        verify(chunkMapper, never()).delete(any());
        verify(qdrantIndexService, never()).replaceMediaIndex(any());
    }

    @Test
    void keepsTheMysqlIndexReadyWhenQdrantIsUnavailable() {
        mapperBackedByMemory();
        when(contextService.build(42L)).thenReturn(context(
                "t1-v1-p2",
                List.of(transcript(1L, 0, "first")),
                List.of()
        ));
        when(summarizer.summarize(any())).thenReturn(
                new VideoChunkSummarizer.ChunkSummary("summary", List.of("topic"))
        );
        when(embeddingClient.embedDocuments(any())).thenReturn(new AliyunTextEmbeddingClient.EmbeddingBatch(
                List.of(vector(1)),
                8
        ));
        doThrow(new QdrantKnowledgeIndexService.QdrantUnavailableException(
                "down", new IllegalStateException("connection refused")
        )).when(qdrantIndexService).replaceMediaIndex(any());

        VideoKnowledgeIndexService.IndexSnapshot result = service.build(42L);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().getFirst().getStatus()).isEqualTo("READY");
        verify(chunkMapper).delete(any());
    }

    @SafeVarargs
    private List<VideoKnowledgeChunk> mapperBackedByMemory(VideoKnowledgeChunk... initial) {
        List<VideoKnowledgeChunk> rows = new ArrayList<>(List.of(initial));
        AtomicLong ids = new AtomicLong(10);
        when(chunkMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(rows));
        when(chunkMapper.insert(any(VideoKnowledgeChunk.class))).thenAnswer(invocation -> {
            VideoKnowledgeChunk chunk = invocation.getArgument(0);
            chunk.setId(ids.getAndIncrement());
            rows.add(chunk);
            return 1;
        });
        when(chunkMapper.updateById(any(VideoKnowledgeChunk.class))).thenReturn(1);
        when(chunkMapper.delete(any())).thenReturn(0);
        return rows;
    }

    private VideoKnowledgeChunk chunk(String version, int index, String status) {
        VideoKnowledgeChunk chunk = new VideoKnowledgeChunk();
        chunk.setMediaId(42L);
        chunk.setContextVersion(version);
        chunk.setProcessorVersion(1);
        chunk.setChunkIndex(index);
        chunk.setStartMs(index * VideoKnowledgeIndexService.CHUNK_MS);
        chunk.setEndMs((index + 1L) * VideoKnowledgeIndexService.CHUNK_MS);
        chunk.setStatus(status);
        return chunk;
    }

    private VideoContext context(
            String version,
            List<VideoContext.TranscriptEvidence> transcripts,
            List<VideoContext.VisualEvidence> visuals
    ) {
        return new VideoContext(
                42L,
                version,
                "ALIYUN_ASR",
                new VideoContext.Modality("AVAILABLE", 1, transcripts.size(), transcripts.size(), null),
                new VideoContext.Modality("AVAILABLE", 1, visuals.size(), visuals.size(), null),
                transcripts,
                visuals
        );
    }

    private VideoContext.TranscriptEvidence transcript(Long id, long startMs, String text) {
        return new VideoContext.TranscriptEvidence(id, startMs, startMs + 1_000, text);
    }

    private VideoContext.VisualEvidence visual(Long id, long timestampMs, String text) {
        return new VideoContext.VisualEvidence(
                id, timestampMs, "frame.jpg", "SUCCEEDED", 90.0, text
        );
    }

    private List<Double> vector(double first) {
        List<Double> vector = new ArrayList<>(Collections.nCopies(
                AliyunTextEmbeddingClient.DIMENSION, 0.0
        ));
        vector.set(0, first);
        return vector;
    }
}

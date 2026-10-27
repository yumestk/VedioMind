package com.example.server.service;

import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoKnowledgeRetrieverTest {

    private final VideoKnowledgeIndexService indexService = mock(VideoKnowledgeIndexService.class);
    private final AliyunTextEmbeddingClient embeddingClient = mock(AliyunTextEmbeddingClient.class);
    private final QdrantKnowledgeIndexService qdrantIndexService = mock(QdrantKnowledgeIndexService.class);
    private final VideoKnowledgeRetriever retriever = new VideoKnowledgeRetriever(
            indexService, embeddingClient, qdrantIndexService
    );

    @Test
    void ranksSemanticallyRelevantChunksAndReturnsSentenceEvidenceIds() {
        VideoContext context = new VideoContext(
                42L,
                "t1-v1-p2",
                "ALIYUN_ASR",
                new VideoContext.Modality("AVAILABLE", 1, 2, 2, null),
                new VideoContext.Modality("AVAILABLE", 1, 0, 0, null),
                List.of(
                        new VideoContext.TranscriptEvidence(1L, 0, 1_000, "network basics"),
                        new VideoContext.TranscriptEvidence(2L, 300_000, 301_000, "database index")
                ),
                List.of()
        );
        VideoKnowledgeChunk networking = chunk(0, "network", "[\"network\"]", "[1.0,0.0]");
        VideoKnowledgeChunk database = chunk(1, "database", "[\"database\"]", "[0.0,1.0]");
        when(indexService.requireReady(42L)).thenReturn(
                new VideoKnowledgeIndexService.IndexSnapshot(
                        context, List.of(networking, database), "summaries"
                )
        );
        when(embeddingClient.embedQuery(eq("network question"), any())).thenReturn(List.of(1.0, 0.0));

        List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits = retriever.search(
                42L, "network question"
        ).hits();

        assertThat(hits).extracting(VideoKnowledgeRetriever.KnowledgeSearchHit::chunkIndex)
                .containsExactly(0, 1);
        assertThat(hits.get(0).transcriptSegmentIds()).containsExactly(1L);
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void excludesVisualOnlyChunksFromTranscriptQuestionEvidence() {
        VideoContext context = new VideoContext(
                42L,
                "t1-v1-p2",
                "ALIYUN_ASR",
                new VideoContext.Modality("AVAILABLE", 1, 1, 1, null),
                new VideoContext.Modality("AVAILABLE", 1, 1, 1, null),
                List.of(new VideoContext.TranscriptEvidence(
                        2L, 300_000, 301_000, "database index"
                )),
                List.of(new VideoContext.VisualEvidence(
                        3L, 1_000, "frame.jpg", "SUCCEEDED", 90.0, "visual title"
                ))
        );
        VideoKnowledgeChunk visualOnly = chunk(0, "visual", "[\"visual\"]", "[1.0,0.0]");
        VideoKnowledgeChunk transcript = chunk(1, "database", "[\"database\"]", "[0.0,1.0]");
        when(indexService.requireReady(42L)).thenReturn(
                new VideoKnowledgeIndexService.IndexSnapshot(
                        context, List.of(visualOnly, transcript), "summaries"
                )
        );
        when(embeddingClient.embedQuery(eq("visual question"), any())).thenReturn(List.of(1.0, 0.0));

        List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits = retriever.search(
                42L, "visual question"
        ).hits();

        assertThat(hits).extracting(VideoKnowledgeRetriever.KnowledgeSearchHit::chunkIndex)
                .containsExactly(1);
        assertThat(hits.getFirst().transcriptSegmentIds()).containsExactly(2L);
        assertThat(retriever.searchMultimodal(42L, "visual question").getFirst().visualEvidenceIds())
                .containsExactly(3L);
    }

    @Test
    void reranksOnlyQdrantCandidatesWhenTheIndexIsComplete() {
        VideoContext context = new VideoContext(
                42L,
                "t1-v1-p2",
                "ALIYUN_ASR",
                new VideoContext.Modality("AVAILABLE", 1, 0, 0, null),
                new VideoContext.Modality("UNAVAILABLE", 0, 0, 0, null),
                List.of(),
                List.of()
        );
        List<VideoKnowledgeChunk> chunks = IntStream.range(0, 13)
                .mapToObj(index -> chunk(
                        index,
                        "chunk " + index,
                        "[]",
                        index == 12 ? "[]" : "[1.0,0.0]"
                ))
                .toList();
        when(embeddingClient.embedQuery(eq("topic"), any())).thenReturn(List.of(1.0, 0.0));
        when(qdrantIndexService.search(
                42L, "t1-v1-p2", 1, List.of(1.0, 0.0), 12
        )).thenReturn(IntStream.rangeClosed(1, 12).mapToObj(Long::valueOf).toList());

        List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits = retriever.searchMultimodal(
                new VideoKnowledgeIndexService.IndexSnapshot(context, chunks, "summaries"),
                "topic"
        );

        assertThat(hits).hasSize(4);
        verify(qdrantIndexService).search(
                42L, "t1-v1-p2", 1, List.of(1.0, 0.0), 12
        );
    }

    private VideoKnowledgeChunk chunk(int index, String summary, String keywords, String embedding) {
        VideoKnowledgeChunk chunk = new VideoKnowledgeChunk();
        chunk.setChunkIndex(index);
        chunk.setId((long) index + 1);
        chunk.setMediaId(42L);
        chunk.setContextVersion("t1-v1-p2");
        chunk.setProcessorVersion(1);
        chunk.setStartMs(index * VideoKnowledgeIndexService.CHUNK_MS);
        chunk.setEndMs((index + 1L) * VideoKnowledgeIndexService.CHUNK_MS);
        chunk.setSummary(summary);
        chunk.setKeywordsJson(keywords);
        chunk.setEmbeddingJson(embedding);
        chunk.setStatus("READY");
        return chunk;
    }
}

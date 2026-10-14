package com.example.server.service;

import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.google.common.util.concurrent.Futures;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.qdrant.client.PointIdFactory.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantKnowledgeIndexServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final QdrantClient client = mock(QdrantClient.class);
    private final QdrantKnowledgeIndexService service = new QdrantKnowledgeIndexService(
            client, "knowledge", TIMEOUT
    );

    @Test
    void exposesTheProductionConstructorToSpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(QdrantKnowledgeIndexService.class);
            context.refresh();

            assertThat(context.getBean(QdrantKnowledgeIndexService.class)).isNotNull();
        }
    }

    @Test
    void replacesMediaPointsWithStableChunkIdsAndMetadata() {
        when(client.collectionExistsAsync("knowledge", TIMEOUT))
                .thenReturn(Futures.immediateFuture(true));
        when(client.deleteAsync(any(Points.DeletePoints.class), eq(TIMEOUT)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.upsertAsync(any(Points.UpsertPoints.class), eq(TIMEOUT)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));

        service.replaceMediaIndex(List.of(chunk(7L, 42L, "context-v1", 2)));

        ArgumentCaptor<Points.DeletePoints> delete = ArgumentCaptor.forClass(Points.DeletePoints.class);
        verify(client).deleteAsync(delete.capture(), eq(TIMEOUT));
        assertThat(delete.getValue().getWait()).isTrue();
        assertThat(delete.getValue().getPoints().getFilter().getMustCount()).isEqualTo(1);

        ArgumentCaptor<Points.UpsertPoints> upsert = ArgumentCaptor.forClass(Points.UpsertPoints.class);
        verify(client).upsertAsync(upsert.capture(), eq(TIMEOUT));
        Points.PointStruct point = upsert.getValue().getPoints(0);
        assertThat(upsert.getValue().getWait()).isTrue();
        assertThat(point.getId().getNum()).isEqualTo(7L);
        assertThat(point.getPayloadOrThrow("media_id").getIntegerValue()).isEqualTo(42L);
        assertThat(point.getPayloadOrThrow("context_version").getStringValue())
                .isEqualTo("context-v1");
        assertThat(point.getVectors().getVector().getDense().getDataCount())
                .isEqualTo(AliyunTextEmbeddingClient.DIMENSION);
    }

    @Test
    void searchesWithinTheCurrentMediaAndContextAndReturnsChunkIds() {
        when(client.collectionExistsAsync("knowledge", TIMEOUT))
                .thenReturn(Futures.immediateFuture(true));
        Points.ScoredPoint result = Points.ScoredPoint.newBuilder().setId(id(7L)).build();
        when(client.searchAsync(any(Points.SearchPoints.class), eq(TIMEOUT)))
                .thenReturn(Futures.immediateFuture(List.of(result)));

        List<Long> ids = service.search(42L, "context-v1", 1, vector(1), 12);

        assertThat(ids).containsExactly(7L);
        ArgumentCaptor<Points.SearchPoints> search = ArgumentCaptor.forClass(Points.SearchPoints.class);
        verify(client).searchAsync(search.capture(), eq(TIMEOUT));
        assertThat(search.getValue().getLimit()).isEqualTo(12);
        assertThat(search.getValue().getFilter().getMustCount()).isEqualTo(3);
        assertThat(search.getValue().getWithPayload().getEnable()).isFalse();
    }

    @Test
    void classifiesRemoteFailuresAsUnavailableForCallerFallback() {
        when(client.collectionExistsAsync("knowledge", TIMEOUT)).thenReturn(
                Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException())
        );

        assertThatThrownBy(() -> service.search(42L, "context-v1", 1, vector(1), 12))
                .isInstanceOf(QdrantKnowledgeIndexService.QdrantUnavailableException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    private VideoKnowledgeChunk chunk(Long id, Long mediaId, String contextVersion, int chunkIndex) {
        VideoKnowledgeChunk chunk = new VideoKnowledgeChunk();
        chunk.setId(id);
        chunk.setMediaId(mediaId);
        chunk.setContextVersion(contextVersion);
        chunk.setProcessorVersion(1);
        chunk.setChunkIndex(chunkIndex);
        chunk.setStartMs(600_000L);
        chunk.setEndMs(900_000L);
        chunk.setEmbeddingJson(com.alibaba.fastjson2.JSON.toJSONString(vector(1)));
        return chunk;
    }

    private List<Double> vector(double first) {
        List<Double> vector = new ArrayList<>(java.util.Collections.nCopies(
                AliyunTextEmbeddingClient.DIMENSION, 0.0
        ));
        vector.set(0, first);
        return vector;
    }
}

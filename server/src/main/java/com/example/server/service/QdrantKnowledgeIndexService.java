package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

@Service
public class QdrantKnowledgeIndexService {

    static final int VECTOR_DIMENSION = AliyunTextEmbeddingClient.DIMENSION;

    private final QdrantClient client;
    private final String collection;
    private final Duration timeout;
    private volatile boolean collectionReady;

    public QdrantKnowledgeIndexService(
            @Value("${qdrant.host:localhost}") String host,
            @Value("${qdrant.grpc-port:6334}") int grpcPort,
            @Value("${qdrant.collection:video_knowledge_chunks_v1}") String collection,
            @Value("${qdrant.api-key:}") String apiKey,
            @Value("${qdrant.timeout-seconds:3}") long timeoutSeconds
    ) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, grpcPort, false)
                .withTimeout(Duration.ofSeconds(timeoutSeconds));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        this.client = new QdrantClient(builder.build());
        this.collection = collection;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    QdrantKnowledgeIndexService(QdrantClient client, String collection, Duration timeout) {
        this.client = client;
        this.collection = collection;
        this.timeout = timeout;
    }

    public void replaceMediaIndex(List<VideoKnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("Qdrant index requires at least one knowledge chunk");
        }
        Long mediaId = chunks.getFirst().getMediaId();
        ensureCollection();
        deleteByMediaId(mediaId);
        List<Points.PointStruct> points = chunks.stream()
                .map(this::point)
                .toList();
        await(client.upsertAsync(Points.UpsertPoints.newBuilder()
                .setCollectionName(collection)
                .setWait(true)
                .addAllPoints(points)
                .build(), timeout));
    }

    public List<Long> search(
            Long mediaId,
            String contextVersion,
            int processorVersion,
            List<Double> queryVector,
            int limit
    ) {
        validateVector(queryVector);
        ensureCollection();
        Common.Filter filter = Common.Filter.newBuilder()
                .addMust(match("media_id", mediaId))
                .addMust(matchKeyword("context_version", contextVersion))
                .addMust(match("processor_version", processorVersion))
                .build();
        Points.SearchPoints request = Points.SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(toFloats(queryVector))
                .setFilter(filter)
                .setLimit(limit)
                .setWithPayload(enable(false))
                .build();
        return await(client.searchAsync(request, timeout)).stream()
                .filter(point -> point.hasId() && point.getId().hasNum())
                .map(point -> point.getId().getNum())
                .toList();
    }

    public void deleteMedia(Long mediaId) {
        if (mediaId == null) {
            return;
        }
        ensureCollection();
        deleteByMediaId(mediaId);
    }

    @PreDestroy
    void close() {
        client.close();
    }

    private synchronized void ensureCollection() {
        if (collectionReady) {
            return;
        }
        if (!await(client.collectionExistsAsync(collection, timeout))) {
            Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                    .setSize(VECTOR_DIMENSION)
                    .setDistance(Collections.Distance.Cosine)
                    .build();
            await(client.createCollectionAsync(collection, vectorParams, timeout));
        }
        collectionReady = true;
    }

    private void deleteByMediaId(Long mediaId) {
        Common.Filter filter = Common.Filter.newBuilder()
                .addMust(match("media_id", mediaId))
                .build();
        await(client.deleteAsync(Points.DeletePoints.newBuilder()
                .setCollectionName(collection)
                .setWait(true)
                .setPoints(Points.PointsSelector.newBuilder().setFilter(filter))
                .build(), timeout));
    }

    private Points.PointStruct point(VideoKnowledgeChunk chunk) {
        if (chunk.getId() == null || chunk.getMediaId() == null
                || chunk.getContextVersion() == null || chunk.getContextVersion().isBlank()) {
            throw new IllegalStateException("Knowledge chunk is missing Qdrant identity metadata");
        }
        List<Double> embedding = JSON.parseArray(chunk.getEmbeddingJson(), Double.class);
        validateVector(embedding);
        return Points.PointStruct.newBuilder()
                .setId(id(chunk.getId()))
                .setVectors(vectors(toFloats(embedding)))
                .putPayload("media_id", value(chunk.getMediaId()))
                .putPayload("context_version", value(chunk.getContextVersion()))
                .putPayload("processor_version", value(chunk.getProcessorVersion()))
                .putPayload("chunk_index", value(chunk.getChunkIndex()))
                .putPayload("start_ms", value(chunk.getStartMs()))
                .putPayload("end_ms", value(chunk.getEndMs()))
                .build();
    }

    private void validateVector(List<Double> vector) {
        if (vector == null || vector.size() != VECTOR_DIMENSION) {
            throw new IllegalStateException("Knowledge embedding dimension must be " + VECTOR_DIMENSION);
        }
    }

    private List<Float> toFloats(List<Double> vector) {
        return vector.stream().map(Double::floatValue).toList();
    }

    private <T> T await(ListenableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new QdrantUnavailableException("Qdrant request was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            Status.Code code = Status.fromThrowable(cause).getCode();
            throw new QdrantUnavailableException("Qdrant request failed: " + code, cause);
        }
    }

    public static class QdrantUnavailableException extends RuntimeException {
        public QdrantUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

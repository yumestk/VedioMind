package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.context.VideoContext;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class VideoKnowledgeRetriever {

    static final int TOP_K = 4;
    private final VideoKnowledgeIndexService indexService;
    private final AliyunTextEmbeddingClient embeddingClient;

    public VideoKnowledgeRetriever(
            VideoKnowledgeIndexService indexService,
            AliyunTextEmbeddingClient embeddingClient
    ) {
        this.indexService = indexService;
        this.embeddingClient = embeddingClient;
    }

    public List<KnowledgeSearchHit> search(Long mediaId, String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Knowledge search query must not be empty");
        }
        VideoKnowledgeIndexService.IndexSnapshot index = indexService.requireReady(mediaId);
        List<Double> queryVector = embeddingClient.embedQuery(query.trim());
        return index.chunks().stream()
                .map(chunk -> hit(index.context(), query, queryVector, chunk))
                .filter(hit -> !hit.transcriptSegmentIds().isEmpty())
                .sorted(Comparator.comparingDouble(KnowledgeSearchHit::score).reversed()
                        .thenComparingLong(KnowledgeSearchHit::startMs))
                .limit(TOP_K)
                .toList();
    }

    private KnowledgeSearchHit hit(
            VideoContext context,
            String query,
            List<Double> queryVector,
            VideoKnowledgeChunk chunk
    ) {
        List<Double> chunkVector = JSON.parseArray(chunk.getEmbeddingJson(), Double.class);
        List<String> keywords = JSON.parseArray(chunk.getKeywordsJson(), String.class);
        double semanticScore = (cosine(queryVector, chunkVector) + 1) / 2;
        double keywordScore = keywordScore(query, keywords);
        List<Long> segmentIds = context.transcriptEvidence().stream()
                .filter(segment -> segment.startMs() >= chunk.getStartMs()
                        && segment.startMs() < chunk.getEndMs())
                .map(VideoContext.TranscriptEvidence::id)
                .toList();
        return new KnowledgeSearchHit(
                chunk.getChunkIndex(),
                chunk.getStartMs(),
                chunk.getEndMs(),
                chunk.getSummary(),
                keywords,
                semanticScore * 0.8 + keywordScore * 0.2,
                segmentIds
        );
    }

    private double keywordScore(String query, List<String> keywords) {
        if (keywords.isEmpty()) {
            return 0;
        }
        String normalizedQuery = normalize(query);
        long matches = keywords.stream()
                .map(this::normalize)
                .filter(keyword -> !keyword.isBlank() && normalizedQuery.contains(keyword))
                .count();
        return (double) matches / keywords.size();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || left.size() != right.size()) {
            throw new IllegalStateException("Knowledge index contains an incompatible embedding");
        }
        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftLength += leftValue * leftValue;
            rightLength += rightValue * rightValue;
        }
        if (leftLength == 0 || rightLength == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[\\s\\p{Punct}，。！？、：；]+", "");
    }

    public record KnowledgeSearchHit(
            int chunkIndex,
            long startMs,
            long endMs,
            String summary,
            List<String> keywords,
            double score,
            List<Long> transcriptSegmentIds
    ) {
        public KnowledgeSearchHit {
            keywords = List.copyOf(keywords);
            transcriptSegmentIds = List.copyOf(transcriptSegmentIds);
        }
    }
}

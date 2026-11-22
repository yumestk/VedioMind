package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.service.VideoKnowledgeIndexService.IndexSnapshot;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.context.VideoContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VideoKnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(VideoKnowledgeRetriever.class);
    static final int TOP_K = 4;
    static final int QDRANT_CANDIDATES = 12;
    private static final int FINE_CANDIDATES = 24;
    private static final int FINE_TOP_K = 6;
    private static final Ranking FINE_RANKING = Ranking.CAPPED_DIVERSE;

    enum Ranking { LEGACY, VECTOR, CAPPED, CAPPED_DIVERSE, RRF }
    private final VideoKnowledgeIndexService indexService;
    private final AliyunTextEmbeddingClient embeddingClient;
    private final QdrantKnowledgeIndexService qdrantIndexService;

    public VideoKnowledgeRetriever(
            VideoKnowledgeIndexService indexService,
            AliyunTextEmbeddingClient embeddingClient,
            QdrantKnowledgeIndexService qdrantIndexService
    ) {
        this.indexService = indexService;
        this.embeddingClient = embeddingClient;
        this.qdrantIndexService = qdrantIndexService;
    }

    public SearchResult search(Long mediaId, String query, Runnable beforeEmbedding) {
        var index = indexService.requireReady(mediaId);
        return new SearchResult(index.context(), searchMultimodal(index, query, beforeEmbedding).stream()
                .filter(hit -> !hit.transcriptSegmentIds().isEmpty()).toList());
    }

    public record SearchResult(VideoContext context, List<KnowledgeSearchHit> hits) {
        public SearchResult { hits = List.copyOf(hits); }
    }

    public List<KnowledgeSearchHit> searchMultimodal(Long mediaId, String query) {
        return searchMultimodal(indexService.requireReady(mediaId), query);
    }

    public List<KnowledgeSearchHit> searchMultimodal(
            VideoKnowledgeIndexService.IndexSnapshot index,
            String query
    ) {
        return searchMultimodal(index, query, () -> {});
    }

    public List<KnowledgeSearchHit> searchMultimodal(
            VideoKnowledgeIndexService.IndexSnapshot index, String query, Runnable beforeEmbedding
    ) {
        return searchMultimodal(index, query, beforeEmbedding, null);
    }

    public List<KnowledgeSearchHit> searchMultimodal(VideoKnowledgeIndexService.IndexSnapshot index,
            String query, Runnable beforeEmbedding, java.time.Instant deadline) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Knowledge search query must not be empty");
        }
        long startedAt = System.nanoTime();
        String normalizedQuery = query.trim();
        List<Double> queryVector = deadline == null ? embeddingClient.embedQuery(query.trim(), beforeEmbedding)
                : embeddingClient.embedQuery(query.trim(), beforeEmbedding, deadline);
        return searchWithVector(index, normalizedQuery, queryVector, deadline, startedAt);
    }

    /** Embed one query once; SQL/Qdrant still receive an individual authorized media/version filter. */
    public List<List<KnowledgeSearchHit>> searchMaterials(List<VideoKnowledgeIndexService.IndexSnapshot> indexes,
            String query, Runnable beforeEmbedding) {
        if (indexes.isEmpty() || indexes.size() > StudyTaskPreparationService.MAX_MATERIALS || query == null || query.isBlank())
            throw new IllegalArgumentException("Invalid bounded course search");
        var vector = embeddingClient.embedQuery(query.trim(), beforeEmbedding);
        return indexes.stream().map(index -> searchWithVector(index, query.trim(), vector, null, System.nanoTime())).toList();
    }

    private List<KnowledgeSearchHit> searchWithVector(VideoKnowledgeIndexService.IndexSnapshot index,
            String normalizedQuery, List<Double> queryVector, java.time.Instant deadline, long startedAt) {
        if (index.processorVersion() == VideoKnowledgeIndexService.FINE_VERSION) {
            return rank(index, normalizedQuery, queryVector, FINE_RANKING, FINE_TOP_K, deadline != null);
        }
        return legacy(index, normalizedQuery, queryVector, TOP_K, deadline != null, startedAt);
    }

    List<KnowledgeSearchHit> rank(IndexSnapshot index, String query, List<Double> vector, Ranking ranking, int topK) {
        return rank(index, query, vector, ranking, topK, false);
    }

    private List<KnowledgeSearchHit> rank(IndexSnapshot index, String query, List<Double> vector,
                                          Ranking ranking, int topK, boolean exactOnly) {
        if (ranking == Ranking.LEGACY) return legacy(index, query, vector, topK, exactOnly, System.nanoTime());
        var all = index.chunks();
        var byId = all.stream().collect(Collectors.toMap(VideoKnowledgeChunk::getId, c -> c));
        var vectors = new java.util.HashMap<Long, List<Double>>();
        var scores = new java.util.HashMap<Long, Double>();
        for (var chunk : all) {
            List<Double> embedding = JSON.parseArray(chunk.getEmbeddingJson(), Double.class);
            vectors.put(chunk.getId(), embedding);
            scores.put(chunk.getId(), (cosine(vector, embedding) + 1) / 2);
        }
        Comparator<VideoKnowledgeChunk> semanticOrder = Comparator.<VideoKnowledgeChunk>comparingDouble(
                c -> scores.get(c.getId())).reversed().thenComparingLong(VideoKnowledgeChunk::getStartMs);
        int candidateLimit = ranking == Ranking.VECTOR ? topK : FINE_CANDIDATES;
        List<VideoKnowledgeChunk> semantic = exactOnly ? List.of() : qdrantCandidates(index, vector, byId, candidateLimit);
        if (semantic.isEmpty()) semantic = all.stream().sorted(semanticOrder).limit(candidateLimit).toList();
        if (ranking == Ranking.VECTOR) {
            return semantic.stream().limit(topK).map(c -> hit(index.context(), c, scores.get(c.getId()))).toList();
        }
        var terms = queryTerms(query);
        var lexical = new java.util.HashMap<Long, Double>();
        for (var chunk : all) lexical.put(chunk.getId(), lexicalScore(index.context(), chunk, query, terms));
        if (ranking == Ranking.CAPPED_DIVERSE) {
            return diverse(index, query, semantic, vectors, scores, lexical, topK);
        }
        var semanticRanks = ranks(semantic);
        var lexicalRanks = ranks(all.stream().filter(c -> lexical.get(c.getId()) > 0)
                .sorted(Comparator.<VideoKnowledgeChunk>comparingDouble(c -> lexical.get(c.getId())).reversed()
                        .thenComparing(semanticOrder)).limit(FINE_CANDIDATES).toList());
        var candidates = new java.util.LinkedHashSet<>(semantic);
        if (ranking == Ranking.RRF) all.stream().filter(c -> lexicalRanks.containsKey(c.getId())).forEach(candidates::add);

        return candidates.stream().map(c -> {
                    double score = ranking == Ranking.CAPPED
                            ? 0.8 * scores.get(c.getId()) + lexical.get(c.getId())
                            : reciprocal(semanticRanks.get(c.getId())) + reciprocal(lexicalRanks.get(c.getId()));
                    return hit(index.context(), c, score);
                }).sorted(Comparator.comparingDouble(KnowledgeSearchHit::score).reversed()
                        .thenComparingLong(KnowledgeSearchHit::startMs)).limit(topK).toList();
    }

    private List<KnowledgeSearchHit> diverse(IndexSnapshot index, String query, List<VideoKnowledgeChunk> semantic,
                                             Map<Long, List<Double>> vectors, Map<Long, Double> scores,
                                             Map<Long, Double> exactLexical, int topK) {
        var parts = new java.util.ArrayList<String>();
        parts.add(query);
        if (topK > 4 && ASPECT_CUE.matcher(query).find()) {
            java.util.Arrays.stream(query.split("[，。？；、?!]"))
                    .map(String::strip).filter(part -> part.length() > 4).forEach(parts::add);
        }
        var textScores = chunkTextScores(index, parts);
        Map<Long, Double> bm25 = textScores.getFirst();
        var aspectScores = textScores.subList(1, textScores.size()).stream()
                .map(scoresByAspect -> {
                    double max = scoresByAspect.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    return max == 0 ? Map.<Long, Double>of() : scoresByAspect.entrySet().stream().collect(
                            Collectors.toMap(Map.Entry::getKey, e -> e.getValue() / max));
                }).filter(values -> !values.isEmpty()).toList();
        double maxLexical = bm25.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        var candidates = new java.util.LinkedHashSet<>(semantic);
        if (maxLexical > 0) index.chunks().stream().filter(c -> bm25.get(c.getId()) > 0)
                .sorted(Comparator.<VideoKnowledgeChunk>comparingDouble(c -> bm25.get(c.getId())).reversed())
                .limit(6).forEach(candidates::add);
        var selected = new java.util.ArrayList<VideoKnowledgeChunk>();
        var hits = new java.util.ArrayList<KnowledgeSearchHit>();
        while (hits.size() < topK && !candidates.isEmpty()) {
            var best = candidates.stream().map(c -> {
                double redundancy = selected.stream()
                        .mapToDouble(previous -> cosine(vectors.get(c.getId()), vectors.get(previous.getId())) - 0.45)
                        .max().orElse(0);
                double lexicalBoost = maxLexical == 0 ? 0 : 0.030 * bm25.get(c.getId()) / maxLexical;
                // Keep the leading results stable. Later slots can cover a second clause without a new query embedding.
                double aspectGain = selected.size() < 4 ? 0 : aspectScores.stream().mapToDouble(values ->
                        values.get(c.getId()) - selected.stream().mapToDouble(p -> values.get(p.getId())).max().orElse(0))
                        .max().orElse(0);
                double score = 0.8 * scores.get(c.getId()) + exactLexical.get(c.getId())
                        + lexicalBoost - 0.15 * Math.max(0, redundancy) + 0.06 * Math.max(0, aspectGain);
                return Map.entry(c, score);
            }).max(Map.Entry.comparingByValue()).orElseThrow();
            selected.add(best.getKey());
            candidates.remove(best.getKey());
            hits.add(hit(index.context(), best.getKey(), best.getValue()));
        }
        return List.copyOf(hits);
    }

    private static final java.util.regex.Pattern TEXT_TERM =
            java.util.regex.Pattern.compile("[\\p{IsHan}]+|[A-Za-z_][A-Za-z_0-9]+");

    private static final java.util.regex.Pattern ASPECT_CUE =
            java.util.regex.Pattern.compile("分别|另一|两种|相比|不同|两个|先后|区别|对比|却|一处");

    private static List<Map<Long, Double>> chunkTextScores(IndexSnapshot index, List<String> queries) {
        Map<Long, Map<String, Integer>> documents = new java.util.HashMap<>();
        var lengths = new java.util.HashMap<Long, Integer>();
        var frequencies = new java.util.HashMap<String, Integer>();
        for (var chunk : index.chunks()) {
            StringBuilder content = new StringBuilder(chunk.getSummary()).append(' ')
                    .append(String.join(" ", JSON.parseArray(chunk.getKeywordsJson(), String.class)));
            index.context().transcriptEvidence().stream()
                    .filter(t -> chunk.getStartMs() <= t.startMs() && t.startMs() < chunk.getEndMs())
                    .forEach(t -> content.append(' ').append(t.text()));
            index.context().visualEvidence().stream().filter(v -> v.ocrText() != null
                    && chunk.getStartMs() <= v.timestampMs() && v.timestampMs() < chunk.getEndMs())
                    .forEach(v -> content.append(' ').append(v.ocrText()));
            var counts = textTerms(content.toString());
            lengths.put(chunk.getId(), counts.values().stream().mapToInt(Integer::intValue).sum());
            counts.keySet().forEach(term -> frequencies.merge(term, 1, Integer::sum));
            documents.put(chunk.getId(), counts);
        }
        double average = Math.max(1, lengths.values().stream().mapToInt(Integer::intValue).average().orElse(1));
        return queries.stream().map(query -> {
            Set<String> terms = textTerms(query).keySet();
            Map<Long, Double> result = new java.util.HashMap<>();
            documents.forEach((id, counts) -> {
                double normalization = 1.2 * (0.25 + 0.75 * lengths.get(id) / average);
                double score = terms.stream().filter(counts::containsKey).mapToDouble(term -> {
                    int df = frequencies.get(term);
                    int count = counts.get(term);
                    return Math.log1p((documents.size() - df + 0.5) / (df + 0.5))
                            * (2.2 * count) / (count + normalization);
                }).sum();
                result.put(id, score);
            });
            return result;
        }).toList();
    }

    private static Map<String, Integer> textTerms(String text) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        TEXT_TERM.matcher(text.toLowerCase(java.util.Locale.ROOT)).results().forEach(match -> {
            String term = match.group();
            if (term.charAt(0) < 128) {
                counts.merge(term, 1, Integer::sum);
            } else {
                for (int n = 2; n <= 3; n++) {
                    for (int i = 0; i + n <= term.length(); i++) counts.merge(term.substring(i, i + n), 1, Integer::sum);
                }
            }
        });
        return counts;
    }

    private List<VideoKnowledgeChunk> qdrantCandidates(IndexSnapshot index, List<Double> vector,
                                                       Map<Long, VideoKnowledgeChunk> chunks, int limit) {
        try {
            var ids = qdrantIndexService.search(index.context().mediaId(), index.indexVersion(),
                    index.processorVersion(), vector, limit);
            var found = ids.stream().distinct().map(chunks::get).filter(java.util.Objects::nonNull).toList();
            if (found.size() == Math.min(limit, chunks.size())) return found;
        } catch (QdrantKnowledgeIndexService.QdrantUnavailableException unavailable) {
            log.warn("Knowledge index unavailable for media {}; scanning SQL vectors", index.context().mediaId());
        }
        return List.of();
    }

    private static Map<Long, Integer> ranks(List<VideoKnowledgeChunk> chunks) {
        Map<Long, Integer> ranks = new java.util.HashMap<>();
        for (int i = 0; i < chunks.size(); i++) ranks.put(chunks.get(i).getId(), i + 1);
        return ranks;
    }

    private static double reciprocal(Integer rank) {
        return rank == null ? 0 : 1d / (60 + rank);
    }

    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[a-zA-Z_][a-zA-Z_0-9]*");
    private static final Set<String> GENERIC = Set.of("for", "the", "and", "int", "void", "class", "return",
            "if", "else", "this", "list", "stack", "queue", "front", "rear");

    private static Set<String> queryTerms(String query) {
        return IDENTIFIER.matcher(query).results().map(m -> m.group().toLowerCase(java.util.Locale.ROOT))
                .filter(term -> term.length() >= 3 && !GENERIC.contains(term)).collect(Collectors.toSet());
    }

    private double lexicalScore(VideoContext context, VideoKnowledgeChunk chunk, String query, Set<String> terms) {
        double keyword = 0;
        String normalized = normalize(query);
        for (String term : JSON.parseArray(chunk.getKeywordsJson(), String.class)) {
            var clean = normalize(term);
            if (clean.length() >= 2 && normalized.contains(clean)
                    && (!term.matches("[a-zA-Z_0-9]+") || terms.contains(clean))) {
                keyword = Math.max(keyword, clean.length() == 2 ? 0.002 : 0.008);
            }
        }
        if (terms.isEmpty()) return keyword;
        Set<String> matching = new java.util.HashSet<>();
        for (var frame : context.visualEvidence()) {
            if (frame.ocrText() == null || frame.timestampMs() < chunk.getStartMs()
                    || frame.timestampMs() >= chunk.getEndMs()) continue;
            IDENTIFIER.matcher(frame.ocrText()).results().map(m -> m.group().toLowerCase(java.util.Locale.ROOT))
                    .filter(terms::contains).forEach(matching::add);
        }
        return Math.min(0.024, keyword + 0.010 * matching.size());
    }

    private List<KnowledgeSearchHit> legacy(IndexSnapshot index, String normalizedQuery, List<Double> queryVector,
                                             int topK, boolean exactOnly, long startedAt) {
        CandidateSelection candidates = exactOnly ? new CandidateSelection(index.chunks(), "bounded-single-video-exact")
                : candidates(index, normalizedQuery, queryVector);
        List<KnowledgeSearchHit> hits = candidates.chunks().stream()
                .map(chunk -> hit(index.context(), normalizedQuery, queryVector, chunk))
                .sorted(Comparator.comparingDouble(KnowledgeSearchHit::score).reversed()
                        .thenComparingLong(KnowledgeSearchHit::startMs))
                .limit(topK)
                .toList();
        log.info("Knowledge retrieval source={}, media={}, candidates={}, selectedChunks={}, latencyMs={}",
                candidates.source(), index.context().mediaId(), candidates.chunks().size(),
                hits.stream().map(KnowledgeSearchHit::chunkIndex).toList(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return hits;
    }

    private CandidateSelection candidates(
            VideoKnowledgeIndexService.IndexSnapshot index,
            String query,
            List<Double> queryVector
    ) {
        List<VideoKnowledgeChunk> allChunks = index.chunks();
        int expectedCandidates = Math.min(QDRANT_CANDIDATES, allChunks.size());
        try {
            List<Long> ids = qdrantIndexService.search(
                    index.context().mediaId(),
                    index.indexVersion(),
                    index.processorVersion(),
                    queryVector,
                    QDRANT_CANDIDATES
            );
            Map<Long, VideoKnowledgeChunk> chunksById = allChunks.stream()
                    .filter(chunk -> chunk.getId() != null)
                    .collect(Collectors.toMap(VideoKnowledgeChunk::getId, Function.identity()));
            List<VideoKnowledgeChunk> semanticCandidates = ids.stream()
                    .distinct()
                    .map(chunksById::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (semanticCandidates.size() < expectedCandidates) {
                return new CandidateSelection(allChunks, "mysql-fallback-incomplete-qdrant");
            }

            Set<VideoKnowledgeChunk> combined = new LinkedHashSet<>(semanticCandidates);
            allChunks.stream()
                    .filter(chunk -> keywordScore(query,
                            JSON.parseArray(chunk.getKeywordsJson(), String.class)) > 0)
                    .sorted(Comparator.comparingDouble((VideoKnowledgeChunk chunk) -> keywordScore(
                            query, JSON.parseArray(chunk.getKeywordsJson(), String.class))).reversed())
                    .limit(TOP_K)
                    .forEach(combined::add);
            return new CandidateSelection(List.copyOf(combined), "qdrant-hybrid");
        } catch (QdrantKnowledgeIndexService.QdrantUnavailableException exception) {
            log.warn("Qdrant retrieval unavailable for media {}; using MySQL exact scan: {}",
                    index.context().mediaId(), exception.getMessage());
            return new CandidateSelection(allChunks, "mysql-fallback-unavailable");
        }
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
        return hit(context, chunk, semanticScore * 0.8 + keywordScore * 0.2);
    }

    private KnowledgeSearchHit hit(VideoContext context, VideoKnowledgeChunk chunk, double score) {
        List<String> keywords = JSON.parseArray(chunk.getKeywordsJson(), String.class);
        List<Long> segmentIds = context.transcriptEvidence().stream()
                .filter(segment -> segment.startMs() >= chunk.getStartMs()
                        && segment.startMs() < chunk.getEndMs())
                .map(VideoContext.TranscriptEvidence::id)
                .toList();
        List<Long> visualIds = context.visualEvidence().stream()
                .filter(frame -> frame.ocrText() != null && !frame.ocrText().isBlank())
                .filter(frame -> frame.timestampMs() >= chunk.getStartMs()
                        && frame.timestampMs() < chunk.getEndMs())
                .map(VideoContext.VisualEvidence::id)
                .toList();
        return new KnowledgeSearchHit(
                chunk.getChunkIndex(),
                chunk.getStartMs(),
                chunk.getEndMs(),
                chunk.getSummary(),
                keywords,
                score,
                segmentIds,
                visualIds
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
            List<Long> transcriptSegmentIds,
            List<Long> visualEvidenceIds
    ) {
        public KnowledgeSearchHit {
            keywords = List.copyOf(keywords);
            transcriptSegmentIds = List.copyOf(transcriptSegmentIds);
            visualEvidenceIds = List.copyOf(visualEvidenceIds);
        }
    }

    private record CandidateSelection(List<VideoKnowledgeChunk> chunks, String source) {
    }
}

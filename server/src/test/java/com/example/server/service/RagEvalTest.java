package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.entity.VideoKnowledgeChunk;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.TranscriptSegmentMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.mapper.VideoKnowledgeChunkMapper;
import com.example.server.service.ai.AiCallLedger;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.StudyExecutionContext;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.ai.impl.DeepSeekMessage;
import com.example.server.service.context.VideoContext;
import com.example.server.service.context.VideoContextService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("eval")
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class RagEvalTest {
    private static final Path DATASET = Path.of("src/test/resources/eval/eval-questions.json");
    private static final String DATASET_HASH = "b0a77ad6418ef6c78faf57de4ab68f0116edd3e1239dd20fc2da487e19802b5d";
    private static final List<String> CATEGORIES = List.of("keyword_term", "visual_ocr", "cross_chapter");

    @Test
    void benchmark() throws Exception {
        var settings = new EvalSettings();
        assertEquals(43L, settings.mediaId, "The locked question set belongs to mediaId 43");
        assertEquals(DATASET_HASH, EvalSettings.fileHash(DATASET), "Locked dataset content changed");
        var questions = JSON.parseArray(Files.readString(DATASET), Question.class);
        assertEquals(30, questions.size());
        for (String category : CATEGORIES) {
            assertEquals(10, questions.stream().filter(q -> category.equals(q.category)).count());
        }

        var dataSource = settings.dataSource();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("eval-read", new JdbcTransactionFactory(), dataSource));
        for (var mapper : List.of(MediaFileMapper.class, MediaVisualEvidenceMapper.class, TranscriptSegmentMapper.class,
                VideoKeyframeMapper.class, VideoKnowledgeChunkMapper.class)) {
            configuration.addMapper(mapper);
        }
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        var redis = settings.redis();
        String collection = settings.get("qdrant.collection", "QDRANT_COLLECTION", "video_knowledge_chunks_v1");
        var timeout = Duration.ofSeconds(10);
        var grpc = QdrantGrpcClient.newBuilder(settings.get("qdrant.host", "QDRANT_HOST", "localhost"),
                Integer.parseInt(settings.get("qdrant.grpc-port", "QDRANT_GRPC_PORT", "6334")), false)
                .withTimeout(timeout);
        String qdrantKey = settings.get("qdrant.api-key", "QDRANT_API_KEY", "");
        if (!qdrantKey.isBlank()) grpc.withApiKey(qdrantKey);
        try (var client = new QdrantClient(grpc.build());
             var session = new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
            assertTrue(client.collectionExistsAsync(collection, timeout).get(), "Existing index collection required");
            var mediaMapper = session.getMapper(MediaFileMapper.class);
            var contextService = new VideoContextService(mediaMapper,
                    new TranscriptService(session.getMapper(TranscriptSegmentMapper.class), mediaMapper),
                    session.getMapper(MediaVisualEvidenceMapper.class), session.getMapper(VideoKeyframeMapper.class));
            var rateLimits = new AiRateLimitService(redis, 5, 30, 30, 6, 60);
            var ledger = new AiCallLedger(jdbc, transactions, new StudyRunControl(jdbc, transactions, null),
                    null, new AiRetryExecutor(rateLimits));
            var embedding = new SharedQueryEmbedding(settings, ledger, jdbc);
            var qdrant = new QdrantKnowledgeIndexService(client, collection, timeout);
            var indexService = new VideoKnowledgeIndexService(session.getMapper(VideoKnowledgeChunkMapper.class),
                    contextService, null, embedding, qdrant, redis);
            var index = indexService.requireReady(settings.mediaId);
            var chunkById = index.chunks().stream().collect(Collectors.toMap(VideoKnowledgeChunk::getId, c -> c));
            var strictQdrant = new QdrantKnowledgeIndexService(client, collection, timeout) {
                @Override
                public List<Long> search(Long mediaId, String version, int processor, List<Double> vector, int limit) {
                    try {
                        var ids = super.search(mediaId, version, processor, vector, limit);
                        assertEquals(Math.min(limit, chunkById.size()), ids.stream().distinct().count());
                        assertTrue(chunkById.keySet().containsAll(ids), "Qdrant returned unknown chunk IDs");
                        return ids;
                    } catch (QdrantUnavailableException failure) {
                        throw new AssertionError("Qdrant unavailable for this evaluation", failure);
                    }
                }
            };
            var budget = new AiPromptBudget(
                    Integer.parseInt(settings.get("ai.context.max-estimated-context-tokens", "AI_CONTEXT_MAX_ESTIMATED_CONTEXT_TOKENS", "64000")),
                    Integer.parseInt(settings.get("ai.context.max-output-tokens", "AI_CONTEXT_MAX_OUTPUT_TOKENS", "8192")));
            var evidenceContext = new VideoEvidenceContextService(
                    Integer.parseInt(settings.get("ai.context.neighbor-segments", "AI_CONTEXT_NEIGHBOR_SEGMENTS", "3")),
                    Long.parseLong(settings.get("ai.context.neighbor-seconds", "AI_CONTEXT_NEIGHBOR_SECONDS", "30")));
            var system = new VideoAgentEvidenceService(
                    new VideoKnowledgeRetriever(indexService, embedding, strictQdrant), evidenceContext, budget);
            var workflow = new VideoAgentWorkflowService(null, null, null, null, budget, null);
            String systemPrompt = (String) ReflectionTestUtils.getField(VideoAgentWorkflowService.class, "NOTES_PROMPT");
            var full = index.context().transcriptEvidence().stream().map(RagEvalTest::transcript).toList();
            var rows = new ArrayList<Row>();
            for (Question q : questions) {
                List<Double> vector = embedding.embedQuery(q.question.trim());
                var hybrid = system.retrieve(index, q.question, () -> {});
                var b4 = vectorHits(strictQdrant, index, chunkById, vector, 4);
                var b5 = vectorHits(strictQdrant, index, chunkById, vector, 5);
                var ranked = hybrid.hits().stream().map(h -> new Hit(h.chunkIndex(), h.startMs(), h.endMs())).toList();
                var baselineEvidence = vectorEvidence(index.context(), b4);
                String prefix = "学习目标：\n" + q.question;
                var originals = new LinkedHashMap<String, VideoAgentCitation>();
                hybrid.evidence().forEach(e -> originals.put(e.ref(), e));
                // Run the production request packer without dispatching an LLM request.
                Object packed = ReflectionTestUtils.invokeMethod(workflow, "input", prefix, originals, null);
                String systemInput = ReflectionTestUtils.invokeMethod(packed, "prompt");
                java.util.Set<String> refs = ReflectionTestUtils.invokeMethod(packed, "refs");
                long systemTokens = estimate(budget, systemPrompt, systemInput);
                assertTrue(systemTokens <= budget.inputLimit(), q.id);
                rows.add(new Row(q, b4, b5, ranked,
                        estimate(budget, systemPrompt, material(workflow, prefix, full)),
                        estimate(budget, systemPrompt, material(workflow, prefix, baselineEvidence)),
                        systemTokens, estimate(budget, systemPrompt, material(workflow, prefix, hybrid.evidence())),
                        refs.size(), refs.stream().filter(ref -> "OCR".equals(originals.get(ref).type())).count()));
                System.out.printf("Eval retrieval %s (%d/%d)%n", q.id, rows.size(), questions.size());
            }
            assertEquals(30, embedding.vectors.size());
            session.clearCache();
            assertEquals(index.context().version(), contextService.currentVersion(settings.mediaId),
                    "Source evidence version changed during evaluation");
            EvalSettings.write("eval-rag.md", report(rows, index, collection, budget.inputLimit()));
        } finally {
            redis.shutdown();
        }
    }

    private static final class SharedQueryEmbedding extends AliyunTextEmbeddingClient {
        private final Map<String, List<Double>> vectors = new HashMap<>();
        private final Map<String, String> replayRequests;

        SharedQueryEmbedding(EvalSettings settings, AiCallLedger ledger, JdbcTemplate jdbc) {
            super(settings.aliyunKey(), settings.get("ai.aliyun.embedding-base-url", "ALIYUN_EMBEDDING_BASE_URL",
                    "https://dashscope.aliyuncs.com/api/v1"), ledger);
            String replayMinute = System.getProperty("eval.embeddingReplayMinute");
            if (replayMinute == null) {
                replayRequests = Map.of();
            } else {
                LocalDateTime start = LocalDateTime.parse(replayMinute);
                replayRequests = jdbc.query("""
                        SELECT fingerprint, request_id FROM ai_call_attempts
                        WHERE provider='aliyun-embedding' AND kind='RETRIEVAL' AND status='SUCCEEDED'
                        AND response_json IS NOT NULL AND created_at>=? AND created_at<?
                        """, (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)), start, start.plusMinutes(1))
                        .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                assertEquals(30, replayRequests.size(), "Replay requires the complete 30-query batch");
            }
        }

        @Override
        public List<Double> embedQuery(String query, Runnable beforeAttempt) {
            return vectors.computeIfAbsent(query, text -> {
                if (replayRequests.isEmpty()) return super.embedQuery(text, beforeAttempt);
                var body = JSONObject.of("model", MODEL, "input", JSONObject.of("texts", List.of(text)),
                        "parameters", JSONObject.of("text_type", "query", "dimension", DIMENSION));
                var request = replayRequests.get(AiCallLedger.sha256(AiCallLedger.canonicalJson(body)));
                if (request == null) throw new IllegalStateException("Query is missing from the recorded batch");
                try (var scope = StudyExecutionContext.preprocessing(request)) {
                    return super.embedQuery(text, () -> {
                        throw new IllegalStateException("Replay must use the recorded embedding response");
                    });
                }
            });
        }
    }

    private static List<Hit> vectorHits(QdrantKnowledgeIndexService qdrant, VideoKnowledgeIndexService.IndexSnapshot index,
                                       Map<Long, VideoKnowledgeChunk> chunks, List<Double> vector, int k) {
        return qdrant.search(index.context().mediaId(), index.context().version(),
                        VideoKnowledgeIndexService.PROCESSOR_VERSION, vector, k).stream()
                .map(chunks::get).map(c -> new Hit(c.getChunkIndex(), c.getStartMs(), c.getEndMs())).toList();
    }

    private static VideoAgentCitation transcript(VideoContext.TranscriptEvidence t) {
        return new VideoAgentCitation("TRANSCRIPT:" + t.id(), "TRANSCRIPT", t.id(), t.startMs(), t.endMs(), t.text());
    }

    private static List<VideoAgentCitation> vectorEvidence(VideoContext context, List<Hit> hits) {
        var evidence = new ArrayList<VideoAgentCitation>();
        for (Hit hit : hits) {
            context.transcriptEvidence().stream().filter(t -> hit.contains(t.startMs()))
                    .map(RagEvalTest::transcript).forEach(evidence::add);
            context.visualEvidence().stream().filter(v -> hit.contains(v.timestampMs()))
                    .filter(v -> "SUCCEEDED".equals(v.ocrStatus()) && v.ocrText() != null && !v.ocrText().isBlank())
                    .map(v -> new VideoAgentCitation("OCR:" + v.id(), "OCR", v.id(), v.timestampMs(),
                            v.timestampMs(), v.ocrText())).forEach(evidence::add);
        }
        return evidence;
    }

    private static String material(VideoAgentWorkflowService workflow, String prefix, List<VideoAgentCitation> evidence) {
        return ReflectionTestUtils.invokeMethod(workflow, "material", prefix, evidence);
    }

    private static long estimate(AiPromptBudget budget, String system, String prompt) {
        return budget.estimate(system, List.of(new DeepSeekMessage("user", prompt)));
    }

    private static String report(List<Row> rows, VideoKnowledgeIndexService.IndexSnapshot index,
                                 String collection, int inputLimit) {
        var report = new StringBuilder("""
                ## 长视频 RAG

                """);
        report.append("索引为 `").append(collection).append("`，上下文版本 `").append(index.context().version())
                .append("`，知识块处理器 v").append(VideoKnowledgeIndexService.PROCESSOR_VERSION).append("，")
                .append(index.chunks().size()).append(" 个就绪知识块，")
                .append(index.context().transcriptEvidence().size()).append(" 段字幕。\n\n");
        report.append("""
                A 使用全量字幕。B 按 Qdrant 原始向量排序，Prompt 使用 Top-4 块中的字幕与有效 OCR。System 使用真实混合排序和 `VideoAgentEvidenceService` 配额分配后的字幕与 OCR 窗口，每题复用同一个真实查询向量。System 的排序上限为 4 块。

                三组统一使用生产笔记系统提示词、证据 JSON 序列化以及同一学习目标模板，省略会话状态与任务元数据。统计的是 `AiPromptBudget` 估算输入 Token。System 额外通过生产笔记请求的输入打包逻辑，按预算保留完整证据。DeepSeek 与可选引用命中率均未运行。

                ### 主对照

                | 范围 | 题数 | A Hit Rate@4 / MRR@4 | B Hit Rate@4 | B MRR@4 | System Hit Rate@4 | System MRR@4 | A Avg Prompt Tokens | B Avg Prompt Tokens | System Avg Prompt Tokens | System 对 A 降幅 |
                |---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
                """);
        for (String category : List.of("总体", "keyword_term", "visual_ocr", "cross_chapter")) {
            var set = "总体".equals(category) ? rows : rows.stream().filter(r -> category.equals(r.question.category)).toList();
            double a = set.stream().mapToLong(Row::aTokens).average().orElseThrow();
            double b = set.stream().mapToLong(Row::bTokens).average().orElseThrow();
            double system = set.stream().mapToLong(Row::systemTokens).average().orElseThrow();
            report.append(String.format(Locale.ROOT, "| %s | %d | N/A | %s | %.4f | %s | %.4f | %.1f | %.1f | %.1f | %s |%n",
                    category, set.size(), hitRate(set.stream().map(r -> rank(r.question, r.b4)).toList()),
                    mrr(set.stream().map(r -> rank(r.question, r.b4)).toList()),
                    hitRate(set.stream().map(r -> rank(r.question, r.system)).toList()),
                    mrr(set.stream().map(r -> rank(r.question, r.system)).toList()), a, b, system, EvalSettings.percent(a - system, a)));
        }
        report.append(String.format(Locale.ROOT,
                "%n按估算输入预算 %d，超预算题数为 A %d、B %d、System %d。System 证据窗口初组装时有 %d 题超预算，主表使用生产打包后的输入长度。%n",
                inputLimit, rows.stream().filter(r -> r.aTokens > inputLimit).count(),
                rows.stream().filter(r -> r.bTokens > inputLimit).count(),
                rows.stream().filter(r -> r.systemTokens > inputLimit).count(),
                rows.stream().filter(r -> r.unpackedTokens > inputLimit).count()));
        var cross = rows.stream().filter(r -> "cross_chapter".equals(r.question.category)).toList();
        report.append("\n### 跨章节题全部区间覆盖（仅 C01–C10）\n\n")
                .append("| 截止排名 | Baseline A | Baseline B | System |\n|---|---|---:|---:|\n");
        report.append("| @4 | N/A | ").append(coverage(cross, false, false)).append(" | ").append(coverage(cross, true, false)).append(" |\n");
        report.append("| @5 | N/A | ").append(coverage(cross, false, true)).append(" | ").append(coverage(cross, true, true)).append(" |\n");
        report.append("""

                ### @5 附表

                | 范围 | A Hit Rate@5 / MRR@5 | B Hit Rate@5 | B MRR@5 | System Hit Rate@5 | System MRR@5 |
                |---|---|---:|---:|---:|---:|
                """);
        for (String category : List.of("总体", "keyword_term", "visual_ocr", "cross_chapter")) {
            var set = "总体".equals(category) ? rows : rows.stream().filter(r -> category.equals(r.question.category)).toList();
            var bRanks = set.stream().map(r -> rank(r.question, r.b5)).toList();
            var systemRanks = set.stream().map(r -> rank(r.question, r.system)).toList();
            report.append(String.format(Locale.ROOT, "| %s | N/A | %s | %.4f | %s | %.4f |%n",
                    category, hitRate(bRanks), mrr(bRanks), hitRate(systemRanks), mrr(systemRanks)));
        }
        report.append("""

                ### 逐题明细

                排名采用知识块与标注区间的正时长交集。首个命中排名为 0 表示未命中，MRR 记 0。K01 和 V02 的标注区间按 OR 计算。只有 C01 至 C10 逐区间检查全覆盖。块序号从 0 起。

                | 题号 | B Top-4 块 | B Top-5 块 | System 块（最多 4） | 首命中 B@4 / B@5 / System | 全覆盖 B@4 / B@5 / System | A Token | B Token | System Token | System 打包前 Token | System 打包后证据 / OCR |
                |---|---|---|---|---|---|---:|---:|---:|---:|---:|
                """);
        for (Row r : rows) {
            String coverage = "cross_chapter".equals(r.question.category)
                    ? yes(covered(r.question, r.b4)) + " / " + yes(covered(r.question, r.b5)) + " / " + yes(covered(r.question, r.system)) : "N/A";
            report.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d / %d / %d | %s | %d | %d | %d | %d | %d / %d |%n",
                    r.question.id, indices(r.b4), indices(r.b5), indices(r.system), rank(r.question, r.b4),
                    rank(r.question, r.b5), rank(r.question, r.system), coverage, r.aTokens, r.bTokens,
                    r.systemTokens, r.unpackedTokens, r.evidenceCount, r.ocrCount));
        }
        return report.toString();
    }

    private static String indices(List<Hit> hits) {
        return hits.stream().map(h -> Integer.toString(h.index)).collect(Collectors.joining(", "));
    }

    private static String yes(boolean covered) {
        return covered ? "是" : "否";
    }

    private static String coverage(List<Row> rows, boolean system, boolean top5) {
        long hits = rows.stream().filter(r -> covered(r.question, system ? r.system : top5 ? r.b5 : r.b4)).count();
        return hits + "/" + rows.size() + " (" + EvalSettings.percent(hits, rows.size()) + ")";
    }

    private static String hitRate(List<Integer> ranks) {
        long hits = ranks.stream().filter(r -> r > 0).count();
        return hits + "/" + ranks.size() + " (" + EvalSettings.percent(hits, ranks.size()) + ")";
    }

    private static double mrr(List<Integer> ranks) {
        return ranks.stream().mapToDouble(r -> r == 0 ? 0 : 1d / r).average().orElseThrow();
    }

    private static int rank(Question q, List<Hit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            if (q.expectedTimeRanges.stream().anyMatch(hits.get(i)::overlaps)) return i + 1;
        }
        return 0;
    }

    private static boolean covered(Question q, List<Hit> hits) {
        return q.expectedTimeRanges.stream().allMatch(range -> hits.stream().anyMatch(hit -> hit.overlaps(range)));
    }

    public record Question(String id, String category, String question, List<Range> expectedTimeRanges) {}
    public record Range(long startSec, long endSec) {}
    private record Hit(int index, long startMs, long endMs) {
        boolean overlaps(Range range) {
            return Math.max(startMs, range.startSec * 1000) < Math.min(endMs, range.endSec * 1000);
        }

        boolean contains(long timestamp) {
            return startMs <= timestamp && timestamp < endMs;
        }
    }

    private record Row(Question question, List<Hit> b4, List<Hit> b5, List<Hit> system,
                       long aTokens, long bTokens, long systemTokens, long unpackedTokens,
                       int evidenceCount, long ocrCount) {}
}

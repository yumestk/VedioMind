package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.entity.VideoKnowledgeChunk;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.Points;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.test.util.ReflectionTestUtils;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.TranscriptSegmentMapper;
import com.example.server.mapper.VideoChapterMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.mapper.VideoKnowledgeChunkMapper;
import com.example.server.service.ai.AiCallLedger;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.StudyExecutionContext;
import com.example.server.service.ai.impl.AliyunTextEmbeddingClient;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.context.VideoContextService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("eval")
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class RagV2EvalTest {
    private static final String PURPOSE = "eval-rag-v2-43";
    private static final long MEDIA_ID = 43L;
    private static final String FROZEN_DATASET_HASH = "b0a77ad6418ef6c78faf57de4ab68f0116edd3e1239dd20fc2da487e19802b5d";

    @Test
    @EnabledIfSystemProperty(named = "eval.buildV2", matches = "true")
    void stageIndex() throws Exception {
        var settings = new EvalSettings();
        assertEquals(MEDIA_ID, settings.mediaId);
        var ds = settings.dataSource();
        var jdbc = new JdbcTemplate(ds);
        var tx = new DataSourceTransactionManager(ds);
        var redis = settings.redis();
        var factory = mapperFactory(ds);
        var timeout = Duration.ofSeconds(15);
        String collection = settings.get("qdrant.collection", "QDRANT_COLLECTION", "video_knowledge_chunks_v1");
        try (var client = qdrantClient(settings, timeout);
             var session = factory.openSession(true)) {
            assertTrue(client.collectionExistsAsync(collection, timeout).get());
            var qdrant = new QdrantKnowledgeIndexService(client, collection, timeout);
            var context = contextService(session);
            var rateLimits = new AiRateLimitService(redis, 5, 30, 30, 6, 60);
            String resumeAttempt = System.getProperty("eval.resumeUnknownDocument");
            String resumeFingerprint = resumeAttempt == null ? null : jdbc.queryForObject("""
                    SELECT fingerprint FROM ai_call_attempts WHERE id=? AND purpose=?
                    AND provider='aliyun-embedding' AND status='UNKNOWN'
                    """, String.class, resumeAttempt, PURPOSE);
            var ledger = new AiCallLedger(jdbc, tx, new StudyRunControl(jdbc, tx, null), null, new AiRetryExecutor(rateLimits)) {
                @Override
                public WireResponse exchange(String provider, String kind, JSONObject body, Runnable beforeAttempt,
                                             Instant deadline, Transport transport) {
                    if (StudyExecutionContext.current() == null) {
                        return super.exchange(provider, kind, body, beforeAttempt, deadline, transport);
                    }
                    String fingerprint = AiCallLedger.sha256(AiCallLedger.canonicalJson(body));
                    var saved = jdbc.queryForList("""
                            SELECT request_id FROM ai_call_attempts
                            WHERE purpose=? AND provider=? AND fingerprint=? AND status='SUCCEEDED'
                            AND response_json IS NOT NULL
                            """, String.class, PURPOSE, provider, fingerprint);
                    assertTrue(saved.size() <= 1);
                    if (!saved.isEmpty()) {
                        try (var recorded = StudyExecutionContext.preprocessing(saved.getFirst());
                             var scope = StudyExecutionContext.purpose(PURPOSE)) {
                            return super.exchange(provider, kind, body,
                                    () -> fail("Recorded summary replay attempted a new provider call"), deadline, transport);
                        }
                    }
                    try (var scope = StudyExecutionContext.purpose(PURPOSE)) {
                        return super.exchange(provider, kind, body, () -> {
                            beforeAttempt.run();
                            boolean generation = "deepseek".equals(provider) && "GENERATION".equals(kind);
                            boolean embedding = "aliyun-embedding".equals(provider) && "RETRIEVAL".equals(kind)
                                    && "document".equals(body.getJSONObject("parameters").getString("text_type"));
                            assertTrue(generation || embedding, "Only summaries and document embeddings are authorized");
                            int used = jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ai_call_attempts WHERE purpose=? AND provider=?",
                                    Integer.class, PURPOSE, provider);
                            int limit = generation ? 54 : resumeFingerprint == null ? 6 : 7;
                            assertTrue(used < limit, "Provider attempt budget exhausted");
                            int prior = jdbc.queryForObject("""
                                    SELECT COUNT(*) FROM ai_call_attempts
                                    WHERE purpose=? AND provider=? AND fingerprint=?
                                    """, Integer.class, PURPOSE, provider, fingerprint);
                            boolean explicitResume = embedding && prior == 1 && fingerprint.equals(resumeFingerprint);
                            assertTrue(prior == 0 || explicitResume, "Repeated paid request stopped");
                            System.out.printf("Stage %s request %d/%d%n", provider, used + 1, limit);
                        }, deadline, transport);
                    }
                }
            };
            var budget = new AiPromptBudget(64_000, 8_192);
            String deepSeekKey = settings.get("ai.deepseek.api-key", "DEEPSEEK_API_KEY", "");
            assertFalse(deepSeekKey.isBlank() || deepSeekKey.startsWith("${"), "Configure DeepSeek credentials");
            var summarizer = new VideoChunkSummarizer(new DeepSeekChatClient(deepSeekKey,
                    settings.get("ai.deepseek.base-url", "DEEPSEEK_BASE_URL", "https://api.deepseek.com"), ledger, budget));
            var embedding = new AliyunTextEmbeddingClient(settings.aliyunKey(),
                    settings.get("ai.aliyun.embedding-base-url", "ALIYUN_EMBEDDING_BASE_URL",
                            "https://dashscope.aliyuncs.com/api/v1"), ledger);
            var index = new VideoKnowledgeIndexService(session.getMapper(VideoKnowledgeChunkMapper.class),
                    context, summarizer, embedding, qdrant, redis, session.getMapper(VideoChapterMapper.class), 0);

            var previous = index.requireReady(MEDIA_ID, VideoKnowledgeIndexService.PROCESSOR_VERSION);
            assertEquals(19, previous.chunks().size());
            var source = context.build(MEDIA_ID);
            assertEquals("t1-v1-p2", source.version());
            var plan = index.indexPlan(source, VideoKnowledgeIndexService.FINE_VERSION);
            assertEquals(54, plan.chunks().size(), "The authorized plan has exactly 54 blocks");
            assertTrue(plan.chunks().stream().allMatch(c -> c.endMs() - c.startMs() <= 120_000));
            System.out.println("V2 frozen fingerprint " + plan.version());
            if (!Boolean.getBoolean("eval.stagePaid")) return;

            var ready = index.build(MEDIA_ID, source.version(), VideoKnowledgeIndexService.FINE_VERSION, plan.version());
            assertEquals(54, ready.chunks().size());
            assertEquals(plan.version(), ready.indexVersion());
            assertEquals(19, index.requireReady(MEDIA_ID, VideoKnowledgeIndexService.PROCESSOR_VERSION).chunks().size());
            System.out.println("V2 staged " + ready.chunks().size() + " blocks " + ready.indexVersion());
        } finally {
            redis.shutdown();
        }
    }

    @Test
    void compareRankers() throws Exception {
        var settings = new EvalSettings();
        assertEquals(MEDIA_ID, settings.mediaId);
        var dataset = Path.of("src/test/resources/eval/eval-questions.json");
        assertEquals(FROZEN_DATASET_HASH, EvalSettings.fileHash(dataset));
        var questions = JSON.parseArray(Files.readString(dataset), RagEvalTest.Question.class);
        assertEquals(30, questions.size());
        var ds = settings.dataSource();
        var jdbc = new JdbcTemplate(ds);
        var recorded = queryVectors(jdbc);
        assertEquals(30, recorded.size());
        String collection = settings.get("qdrant.collection", "QDRANT_COLLECTION", "video_knowledge_chunks_v1");
        var timeout = Duration.ofSeconds(15);
        try (var client = qdrantClient(settings, timeout); var session = mapperFactory(ds).openSession()) {
            assertTrue(client.collectionExistsAsync(collection, timeout).get());
            var qdrant = new QdrantKnowledgeIndexService(client, collection, timeout);
            var index = new VideoKnowledgeIndexService(session.getMapper(VideoKnowledgeChunkMapper.class),
                    contextService(session), null, null, qdrant, null, session.getMapper(VideoChapterMapper.class), 0);
            var v1 = index.requireReady(MEDIA_ID, VideoKnowledgeIndexService.PROCESSOR_VERSION);
            var v2 = index.requireReady(MEDIA_ID, VideoKnowledgeIndexService.FINE_VERSION);
            assertEquals(19, v1.chunks().size());
            assertEquals(54, v2.chunks().size());
            verifyPoints(client, collection, v1, timeout);
            verifyPoints(client, collection, v2, timeout);
            var versions = Map.of(v1.indexVersion(), v1.chunks().stream().map(VideoKnowledgeChunk::getId).collect(Collectors.toSet()),
                    v2.indexVersion(), v2.chunks().stream().map(VideoKnowledgeChunk::getId).collect(Collectors.toSet()));
            var strictQdrant = new QdrantKnowledgeIndexService(client, collection, timeout) {
                @Override
                public List<Long> search(Long mediaId, String version, int processor, List<Double> vector, int limit) {
                    var expected = versions.get(version);
                    assertNotNull(expected);
                    try {
                        var result = super.search(mediaId, version, processor, vector, limit);
                        assertEquals(Math.min(limit, expected.size()), result.stream().distinct().count());
                        assertTrue(expected.containsAll(result));
                        return result;
                    } catch (QdrantUnavailableException e) {
                        throw new AssertionError("This comparison requires actual Qdrant results", e);
                    }
                }
            };
            var replayEmbedding = new AliyunTextEmbeddingClient(null, null, null) {
                @Override
                public List<Double> embedQuery(String question, Runnable beforeAttempt, Instant deadline) {
                    var vector = recorded.get(queryFingerprint(question));
                    assertNotNull(vector);
                    return vector;
                }
            };
            var retriever = new VideoKnowledgeRetriever(index, replayEmbedding, strictQdrant);
            var budget = new AiPromptBudget(64_000, 8_192);
            var evidence = new VideoAgentEvidenceService(retriever, new VideoEvidenceContextService(3, 30), budget);
            var workflow = new VideoAgentWorkflowService(null, null, null, null, budget, null);
            var cases = new ArrayList<Comparison>();
            cases.add(new Comparison("v1", v1, VideoKnowledgeRetriever.Ranking.LEGACY, 4));
            for (var ranking : List.of(VideoKnowledgeRetriever.Ranking.CAPPED, VideoKnowledgeRetriever.Ranking.CAPPED_DIVERSE, VideoKnowledgeRetriever.Ranking.RRF)) {
                for (int k : List.of(4, 5)) cases.add(new Comparison("v1", v1, ranking, k));
            }
            for (var ranking : List.of(VideoKnowledgeRetriever.Ranking.VECTOR, VideoKnowledgeRetriever.Ranking.CAPPED, VideoKnowledgeRetriever.Ranking.CAPPED_DIVERSE,
                    VideoKnowledgeRetriever.Ranking.RRF)) {
                for (int k : List.of(4, 5, 6)) cases.add(new Comparison("v2", v2, ranking, k));
            }

            var table = new StringBuilder(
                    "version\tranking\tk\tid\tcategory\tfirstHit\tblockFull\tpromptFull\tavgAInput\tinput\trawInput\toversize\tblocks\trefs\n");
            var full = v1.context().transcriptEvidence().stream().map(t -> new VideoAgentCitation("TRANSCRIPT:" + t.id(),
                    "TRANSCRIPT", t.id(), t.startMs(), t.endMs(), t.text())).toList();
            for (var question : questions) {
                var vector = recorded.get(queryFingerprint(question.question()));
                assertNotNull(vector, question.id());
                var prefix = VideoAgentEvidenceService.goalPrefix(question.question());
                long a = evidence.promptTokens(prefix, full);
                for (var candidate : cases) {
                    var hits = retriever.rank(candidate.index(), question.question(), vector, candidate.ranking(), candidate.k());
                    assertEquals(candidate.k(), hits.size());
                    if ("v2".equals(candidate.version()) && candidate.ranking() == VideoKnowledgeRetriever.Ranking.CAPPED_DIVERSE
                            && candidate.k() == 6) {
                        var expected = hits.stream().map(VideoKnowledgeRetriever.KnowledgeSearchHit::chunkIndex).toList();
                        var active = retriever.searchMultimodal(v2, question.question(), () -> {});
                        assertEquals(expected, active.stream().map(VideoKnowledgeRetriever.KnowledgeSearchHit::chunkIndex).toList());
                        var bounded = retriever.searchMultimodal(v2, question.question(), () -> {}, Instant.now().plusSeconds(30));
                        assertEquals(expected, bounded.stream().map(VideoKnowledgeRetriever.KnowledgeSearchHit::chunkIndex).toList());
                    }
                    var initial = evidence.assemble(candidate.index(), question.question(), hits, prefix);
                    long raw = evidence.promptTokens(prefix, initial);
                    var originals = new LinkedHashMap<String, VideoAgentCitation>();
                    initial.forEach(e -> originals.put(e.ref(), e));
                    Object packed = ReflectionTestUtils.invokeMethod(workflow, "input", prefix, originals, null);
                    String request = ReflectionTestUtils.invokeMethod(packed, "prompt");
                    java.util.Set<String> refs = ReflectionTestUtils.invokeMethod(packed, "refs");
                    long tokens = budget.estimate(VideoAgentWorkflowService.NOTES_PROMPT,
                            List.of(new com.example.server.service.ai.impl.DeepSeekMessage("user", request)));
                    if ("v2".equals(candidate.version())) assertTrue(raw <= VideoAgentEvidenceService.FINE_PROMPT_TARGET);
                    int rank = 0;
                    for (int i = 0; i < hits.size() && rank == 0; i++) {
                        var hit = hits.get(i);
                        if (question.expectedTimeRanges().stream().anyMatch(r -> overlaps(hit.startMs(), hit.endMs(), r))) rank = i + 1;
                    }
                    boolean cross = "cross_chapter".equals(question.category());
                    boolean blockFull = allCovered(question, r -> hits.stream().anyMatch(h -> overlaps(h.startMs(), h.endMs(), r)));
                    boolean evidenceFull = allCovered(question, r -> refs.stream().map(originals::get)
                            .anyMatch(e -> overlaps(e.startMs(), e.endMs(), r)));
                    table.append(String.join("\t", candidate.version(), candidate.ranking().name(), Integer.toString(candidate.k()),
                            question.id(), question.category(), Integer.toString(rank),
                            cross ? Boolean.toString(blockFull) : "-", cross ? Boolean.toString(evidenceFull) : "-",
                            Long.toString(a), Long.toString(tokens), Long.toString(raw),
                            Boolean.toString(raw > budget.inputLimit()), hits.stream().map(h -> Integer.toString(h.chunkIndex()))
                                    .collect(Collectors.joining(",")), Integer.toString(refs.size()))).append('\n');
                }
            }
            EvalSettings.write("rag-v2-trials.tsv", table.toString());
            System.out.println("Replayed " + recorded.size() + " vectors on " + cases.size() + " conditions, verified 19+54 points.");
        }
    }

    private static boolean allCovered(RagEvalTest.Question q, Predicate<RagEvalTest.Range> hits) {
        return q.expectedTimeRanges().stream().allMatch(hits);
    }

    private static boolean overlaps(long start, long end, RagEvalTest.Range range) {
        long from = range.startSec() * 1000, to = range.endSec() * 1000;
        return start == end ? from <= start && start < to : start < to && from < end;
    }

    private static Map<String, List<Double>> queryVectors(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT fingerprint,response_json FROM ai_call_attempts
                WHERE provider='aliyun-embedding' AND kind='RETRIEVAL' AND status='SUCCEEDED'
                AND created_at >= '2026-11-22 12:14:00' AND created_at < '2026-11-22 12:15:00'
                """, (rs, row) -> {
            var vectors = JSON.parseObject(rs.getString(2)).getJSONObject("output").getJSONArray("embeddings");
            assertEquals(1, vectors.size());
            var vector = vectors.getJSONObject(0).getJSONArray("embedding").toJavaList(Double.class);
            assertEquals(1024, vector.size());
            return Map.entry(rs.getString(1), vector);
        }).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String queryFingerprint(String question) {
        var body = JSONObject.of("model", AliyunTextEmbeddingClient.MODEL,
                "input", JSONObject.of("texts", List.of(question.trim())),
                "parameters", JSONObject.of("text_type", "query", "dimension", AliyunTextEmbeddingClient.DIMENSION));
        return AiCallLedger.sha256(AiCallLedger.canonicalJson(body));
    }

    private static void verifyPoints(QdrantClient client, String collection, VideoKnowledgeIndexService.IndexSnapshot index,
                                     Duration timeout) throws Exception {
        var points = client.scrollAsync(Points.ScrollPoints.newBuilder().setCollectionName(collection)
                .setFilter(Common.Filter.newBuilder()
                        .addMust(io.qdrant.client.ConditionFactory.match("media_id", index.context().mediaId()))
                        .addMust(io.qdrant.client.ConditionFactory.matchKeyword("context_version", index.indexVersion()))
                        .addMust(io.qdrant.client.ConditionFactory.match("processor_version", index.processorVersion())))
                .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                .setWithVectors(io.qdrant.client.WithVectorsSelectorFactory.enable(true)).setLimit(100).build(), timeout)
                .get().getResultList();
        var expected = index.chunks().stream().collect(Collectors.toMap(VideoKnowledgeChunk::getId, c -> c));
        assertEquals(expected.size(), points.size());
        for (var point : points) {
            var chunk = expected.remove(point.getId().getNum());
            assertNotNull(chunk);
            assertEquals(chunk.getStartMs().longValue(), point.getPayloadOrThrow("start_ms").getIntegerValue());
            assertEquals(chunk.getEndMs().longValue(), point.getPayloadOrThrow("end_ms").getIntegerValue());
            assertEquals(chunk.getChunkIndex().longValue(), point.getPayloadOrThrow("chunk_index").getIntegerValue());
            assertEquals(index.indexVersion(), point.getPayloadOrThrow("context_version").getStringValue());
            assertEquals(index.processorVersion(), point.getPayloadOrThrow("processor_version").getIntegerValue());
            var qdrant = point.getVectors().getVector().getDense().getDataList();
            var sql = JSON.parseArray(chunk.getEmbeddingJson(), Double.class);
            assertEquals(1024, sql.size());
            assertEquals(1024, qdrant.size());
            double magnitude = Math.sqrt(sql.stream().mapToDouble(x -> x * x).sum());
            for (int i = 0; i < sql.size(); i++) assertEquals(sql.get(i) / magnitude, qdrant.get(i), 0.000005);
        }
        assertTrue(expected.isEmpty());
    }

    private record Comparison(String version, VideoKnowledgeIndexService.IndexSnapshot index,
                              VideoKnowledgeRetriever.Ranking ranking, int k) {}

    static org.apache.ibatis.session.SqlSessionFactory mapperFactory(javax.sql.DataSource ds) {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("eval", new JdbcTransactionFactory(), ds));
        for (var mapper : List.of(MediaFileMapper.class, MediaVisualEvidenceMapper.class, TranscriptSegmentMapper.class,
                VideoKeyframeMapper.class, VideoKnowledgeChunkMapper.class, VideoChapterMapper.class)) {
            configuration.addMapper(mapper);
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    static VideoContextService contextService(org.apache.ibatis.session.SqlSession session) {
        var media = session.getMapper(MediaFileMapper.class);
        return new VideoContextService(media, new TranscriptService(session.getMapper(TranscriptSegmentMapper.class), media),
                session.getMapper(MediaVisualEvidenceMapper.class), session.getMapper(VideoKeyframeMapper.class));
    }

    static QdrantClient qdrantClient(EvalSettings settings, Duration timeout) {
        var grpc = QdrantGrpcClient.newBuilder(settings.get("qdrant.host", "QDRANT_HOST", "localhost"),
                Integer.parseInt(settings.get("qdrant.grpc-port", "QDRANT_GRPC_PORT", "6334")), false).withTimeout(timeout);
        String key = settings.get("qdrant.api-key", "QDRANT_API_KEY", "");
        if (!key.isBlank()) grpc.withApiKey(key);
        return new QdrantClient(grpc.build());
    }
}

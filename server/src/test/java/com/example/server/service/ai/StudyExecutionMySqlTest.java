package com.example.server.service.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.service.*;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import com.example.server.service.ai.impl.*;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Real durable reservations/leases/events on disposable MySQL; only the provider and authorization
 * are test doubles.
 */
@EnabledIfEnvironmentVariable(named = "STUDY_EXECUTION_MYSQL_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudyExecutionMySqlTest {
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    TransactionTemplate tx;
    StudyRunControl runs;
    AiCallLedger ledger;
    PreparedStudyTask task;
    StudyTaskPreparationService tasks = mock(StudyTaskPreparationService.class);
    AiRateLimitService rates = mock(AiRateLimitService.class);
    AtomicReference<Instant> instant = new AtomicReference<>();
    Clock clock =
            new Clock() {
                public ZoneId getZone() {
                    return ZoneId.systemDefault();
                }

                public Clock withZone(ZoneId z) {
                    return this;
                }

                public Instant instant() {
                    return instant.get();
                }
            };
    HttpServer server;

    @BeforeAll
    void schema() {
        String url = System.getenv("STUDY_EXECUTION_MYSQL_URL");
        if (!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?"))
            throw new IllegalArgumentException("Isolated MySQL required");
        var ds =
                new DriverManagerDataSource(
                        url,
                        System.getenv("AUTH_TEST_MYSQL_USER"),
                        System.getenv("AUTH_TEST_MYSQL_PASSWORD"));
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
    }

    @BeforeEach
    void sample() {
        for (String table :
                List.of(
                        "study_run_events",
                        "ai_call_attempts",
                        "study_ai_requests",
                        "video_agent_steps",
                        "video_messages",
                        "video_agent_runs",
                        "video_conversations",
                        "media_files",
                        "users")) jdbc.update("DELETE FROM " + table);
        reset(tasks, rates);
        instant.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        runs = new StudyRunControl(jdbc, manager, tasks, clock);
        ledger =
                new AiCallLedger(
                        jdbc, manager, runs, tasks, new AiRetryExecutor(rates, d -> {}, () -> 0));
        jdbc.update(
                "INSERT INTO users(id,username,password) VALUES"
                        + " (1,'author','hash'),(2,'student','hash'),(3,'other','hash')");
        jdbc.update(
                "INSERT INTO media_files(id,user_id,filename) VALUES (10,1,'synthetic lecture')");
        jdbc.update(
                "INSERT INTO"
                        + " video_conversations(id,media_id,learner_id,title,created_at,updated_at)"
                        + " VALUES (7,10,2,'study',NOW(),NOW())");
        task = fixture(10L, 2L, 7L, null, "notes", "v1").forDeliverables(List.of("NOTE"));
        createRun(task, "r");
    }

    @AfterEach
    void shutdown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    void createRun(PreparedStudyTask prepared, String id) {
        ledger.ensureRequest(prepared);
        tx.executeWithoutResult(
                s -> {
                    jdbc.update(
                            "INSERT INTO"
                                + " video_agent_runs(id,media_id,learner_id,conversation_id,task_scope,prepared_task,goal,context_version,status,created_at,updated_at,deadline_at)"
                                + " VALUES (?,10,2,7,?,?,?,'v1','QUEUED',?,?,?)",
                            id,
                            JSON.toJSONString(prepared.scope()),
                            JSON.toJSONString(prepared),
                            prepared.goal(),
                            runs.now(),
                            runs.now(),
                            runs.now().plusMinutes(10));
                    ledger.attach(prepared, id);
                });
    }

    PreparedStudyTask fixture(
            Long media, Long learner, Long conversation, Long course, String goal, String version) {
        var original = StudyTaskFixtures.video(media, learner, conversation, course, goal, version);
        return PreparedStudyTask.create(
                UUID.randomUUID().toString(),
                learner,
                conversation,
                course,
                media,
                original.courseVersion(),
                original.scope(),
                original.materials(),
                goal,
                goal,
                List.of("NOTE"),
                original.sourcePolicy(),
                original.budget());
    }

    long begin() {
        return runs.begin("r", 0).orElseThrow();
    }

    Map<String, Object> row() {
        return jdbc.queryForMap("SELECT * FROM video_agent_runs WHERE id='r'");
    }

    void advance(long seconds) {
        instant.updateAndGet(t -> t.plusSeconds(seconds));
    }

    JSONObject body() {
        return JSONObject.of(
                "model",
                "synthetic",
                "max_tokens",
                100,
                "messages",
                List.of(Map.of("role", "user", "content", "private task")));
    }

    AiCallLedger.WireResponse response() {
        return new AiCallLedger.WireResponse(
                200,
                "{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"saved"
                    + " answer\"}}]}");
    }

    @Test
    void quotaWaitPausesActiveTimeKeepsWallDeadlineAndDoesNotConsumeFailureCount() {
        var deadline = row().get("deadline_at");
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            advance(10);
            assertThat(runs.pause("r", 0, true, 60, null, false)).isTrue();
        }
        assertThat(row().get("active_remaining_ms")).isEqualTo(110000L);
        assertThat(row().get("retry_count")).isEqualTo(0);
        advance(60);
        long next = runs.begin("r", 1).orElseThrow();
        assertThat(row().get("waiting_ms")).isEqualTo(60000L);
        assertThat(row().get("deadline_at")).isEqualTo(deadline);
        try (var ctx = StudyExecutionContext.open(task, "r", next)) {
            advance(5);
            runs.pause("r", 1, true, 60, null, false);
        }
        assertThat(row().get("active_remaining_ms")).isEqualTo(105000L);
        assertThat(row().get("retry_count")).isEqualTo(0);
    }

    @Test
    void wallDeadlineKeepsRunningWhileWaitingAndCannotBeResetByRecovery() {
        begin();
        runs.pause("r", 0, true, 600, null, false);
        advance(601);
        runs.expireDue();
        assertThat(row())
                .containsEntry("status", "FAILED")
                .containsEntry("stop_code", "TASK_DEADLINE_EXCEEDED");
        assertThat(runs.begin("r", 0)).isEmpty();
    }

    @Test
    void cancelFencesOldWorkerButLateUsageCanSettleExactlyOnce() {
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            String id = ledger.reserve("synthetic", "GENERATION", "a".repeat(64), 100, 100);
            ledger.sending(id);
            runs.cancel("r", 2L);
            assertThatThrownBy(() -> runs.assertLease("r")).isInstanceOf(GovernanceException.class);
            assertThatThrownBy(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                runs.lockLease("r");
                                                jdbc.update(
                                                        "UPDATE video_agent_runs SET"
                                                                + " result_json='{}' WHERE id='r'");
                                            }))
                    .isInstanceOf(GovernanceException.class);
            ledger.settle(id, response(), 10);
            ledger.settle(id, response(), 10);
        }
        assertThat(row())
                .containsEntry("status", "CANCELLED")
                .containsEntry("input_tokens", 20)
                .containsEntry("output_tokens", 5)
                .containsEntry("unknown_calls", 0)
                .containsEntry("accounted_tokens", 25L);
        assertThat(row().get("result_json")).isNull();
        assertThat(runs.events("r", 2L, 0))
                .extracting(e -> e.get("type"))
                .containsExactly(
                        "EXECUTION_STARTED", "CALL_RESERVED", "TASK_STOPPED", "CALL_SETTLED");
        assertThatThrownBy(() -> runs.events("r", 3L, 0))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void reservationCrashIsConservativeUnknownAndNeverAutomaticallyReplayed() {
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            ledger.reserve("synthetic", "GENERATION", "a".repeat(64), 100, 100);
        }
        assertThat(runs.recoverRunning("r", 0)).isFalse();
        assertThat(row())
                .containsEntry("status", "FAILED")
                .containsEntry("stop_code", "MODEL_OUTCOME_UNKNOWN")
                .containsEntry("accounted_tokens", 200L);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_call_attempts", String.class))
                .isEqualTo("UNKNOWN");
        assertThat(runs.begin("r", 0)).isEmpty();
    }

    @Test
    void connectionLossDoesNotSpendAnyAutomaticProviderRetry() {
        long epoch = begin();
        AtomicInteger sent = new AtomicInteger();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            assertThatThrownBy(
                            () ->
                                    ledger.exchange(
                                            "synthetic",
                                            "GENERATION",
                                            body(),
                                            () -> {},
                                            null,
                                            d -> {
                                                sent.incrementAndGet();
                                                throw new java.io.IOException("connection lost");
                                            }))
                    .isInstanceOf(GovernanceException.class)
                    .extracting(e -> ((GovernanceException) e).code())
                    .isEqualTo("MODEL_OUTCOME_UNKNOWN");
        }
        assertThat(sent).hasValue(1);
        assertThat(row()).containsEntry("generation_calls", 1).containsEntry("unknown_calls", 1);
        assertThat(runs.recoverRunning("r", 0)).isFalse();
    }

    @Test
    void responseSavedBeforeCheckpointIsReusedWithoutAnotherPaidCallOrQuotaCheck() {
        AtomicInteger sent = new AtomicInteger();
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch);
                var purpose = StudyExecutionContext.purpose("NOTES:0:0")) {
            assertThat(
                            ledger.exchange(
                                            "synthetic",
                                            "GENERATION",
                                            body(),
                                            () -> {},
                                            null,
                                            d -> {
                                                sent.incrementAndGet();
                                                return response();
                                            })
                                    .body())
                    .contains("saved answer");
        }
        assertThat(runs.recoverRunning("r", 0)).isTrue();
        long next = runs.begin("r", 1).orElseThrow();
        clearInvocations(rates);
        try (var ctx = StudyExecutionContext.open(task, "r", next);
                var purpose = StudyExecutionContext.purpose("NOTES:0:0")) {
            assertThat(
                            ledger.exchange(
                                            "synthetic",
                                            "GENERATION",
                                            body(),
                                            () -> {},
                                            null,
                                            d -> {
                                                throw new AssertionError("replayed network call");
                                            })
                                    .body())
                    .contains("saved answer");
        }
        assertThat(sent).hasValue(1);
        verifyNoInteractions(rates);
        assertThat(row()).containsEntry("generation_calls", 1);
    }

    @Test
    void retriesAreIndividualAttemptsAndKnownUsageReplacesOnlyItsOwnReservation() {
        long epoch = begin();
        AtomicInteger sent = new AtomicInteger();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            ledger.exchange(
                    "synthetic",
                    "GENERATION",
                    body(),
                    () -> {},
                    null,
                    d ->
                            sent.incrementAndGet() < 3
                                    ? new AiCallLedger.WireResponse(503, "{}")
                                    : response());
            assertThatThrownBy(
                            () ->
                                    ledger.exchange(
                                            "synthetic",
                                            "GENERATION",
                                            JSONObject.of("max_tokens", 100, "new", "body"),
                                            () -> {},
                                            null,
                                            d -> response()))
                    .isInstanceOf(GovernanceException.class);
        }
        assertThat(sent).hasValue(3);
        assertThat(row()).containsEntry("generation_calls", 3).containsEntry("unknown_calls", 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_attempts", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void unknownUsageOnSuccessfulResponseRetainsReservationAndStillAllowsSafeReplay() {
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            ledger.exchange(
                    "synthetic",
                    "GENERATION",
                    body(),
                    () -> {},
                    null,
                    d ->
                            new AiCallLedger.WireResponse(
                                    200,
                                    "{\"answer\":\"saved\",\"usage\":{\"prompt_tokens\":\"invalid\"}}"));
            ledger.exchange(
                    "synthetic",
                    "GENERATION",
                    body(),
                    () -> {},
                    null,
                    d -> {
                        throw new AssertionError("duplicate");
                    });
        }
        assertThat(row()).containsEntry("unknown_calls", 1).containsEntry("generation_calls", 1);
        assertThat((Long) row().get("accounted_tokens")).isGreaterThan(100);
    }

    @Test
    void sourceChangeAtResumePreventsAnyNewLeaseOrPaidWork() {
        doThrow(new StudyTaskPreparationService.SourceChangedException("changed"))
                .when(tasks)
                .assertCurrent(task);
        assertThatThrownBy(() -> runs.begin("r", 0))
                .isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_call_attempts", Integer.class))
                .isZero();
    }

    @Test
    void needsInputIsTerminalAndReleasesTheConversationActiveConstraint() {
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            runs.stop("r", "TASK_NEEDS_INPUT", "请选择范围", "NEEDS_INPUT", true);
        }
        assertThat(row()).containsEntry("status", "NEEDS_INPUT");
        assertThat(runs.begin("r", 0)).isEmpty();
        createRun(fixture(10L, 2L, 7L, null, "new notes", "v1"), "r2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void anotherStudentCannotCancelAndEventsNeverExposeInternalModelText() {
        assertThatThrownBy(() -> runs.cancel("r", 3L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> runs.cancel("missing", 2L))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> runs.events("missing", 2L, 0))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(row().get("status")).isEqualTo("QUEUED");
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            ledger.exchange("synthetic", "GENERATION", body(), () -> {}, null, d -> response());
        }
        String visible = JSON.toJSONString(runs.events("r", 2L, 0));
        assertThat(visible)
                .doesNotContain("private task", "saved answer", "response_json", "choices");
        assertThat(ledger.studentUsage(2L).values())
                .anySatisfy(v -> assertThat(((Number) v).longValue()).isGreaterThan(0));
        assertThat(ledger.studentUsage(3L).values())
                .allSatisfy(v -> assertThat(((Number) v).longValue()).isZero());
    }

    @Test
    void realHttpCancelIsBestEffortAndLeavesUnknownCostWithoutLateDelivery() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                e -> {
                    sent.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                        byte[] b = response().body().getBytes();
                        e.sendResponseHeaders(200, b.length);
                        e.getResponseBody().write(b);
                    } catch (Exception ignored) {
                    } finally {
                        e.close();
                    }
                });
        server.start();
        var client =
                new DeepSeekChatClient(
                        "synthetic",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        ledger,
                        new AiPromptBudget(64000, 8192));
        long epoch = begin();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result =
                    pool.submit(
                            () -> {
                                try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
                                    return client.chat(
                                            "system", List.of(new DeepSeekMessage("user", "q")));
                                }
                            });
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            runs.cancel("r", 2L);
            ledger.cancelHttp("r");
            release.countDown();
            assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        } finally {
            release.countDown();
        }
        assertThat(sent).hasValue(1);
        assertThat(row()).containsEntry("status", "CANCELLED").containsEntry("unknown_calls", 1);
        assertThat(row().get("result_json")).isNull();
    }

    @Test
    void routingCostIsCarriedIntoRunAndCannotBeErasedByRecovery() {
        jdbc.update("UPDATE video_agent_runs SET status='SUCCEEDED' WHERE id='r'");
        var next = fixture(10L, 2L, 7L, null, "new task", "v1");
        try (var ctx = ledger.open(next);
                var purpose = StudyExecutionContext.purpose("ROUTING")) {
            ledger.exchange("synthetic", "GENERATION", body(), () -> {}, null, d -> response());
        }
        createRun(next, "r2");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation_calls FROM video_agent_runs WHERE id='r2'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT run_id FROM ai_call_attempts", String.class))
                .isEqualTo("r2");
        runs.begin("r2", 0);
        runs.pause("r2", 0, true, 60, null, false);
        advance(60);
        runs.begin("r2", 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation_calls FROM video_agent_runs WHERE id='r2'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleOrDuplicateDeliveryCannotAcquireNewLeaseOrResetFailureCount() {
        long epoch = begin();
        assertThat(runs.begin("r", 0)).isEmpty();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            runs.pause("r", 0, false, 10, "transient", false);
        }
        assertThat(runs.begin("r", 0)).isEmpty();
        assertThat(row().get("retry_count")).isEqualTo(1);
        advance(10);
        assertThat(runs.begin("r", 1)).isPresent();
        assertThat(row().get("retry_count")).isEqualTo(1);
    }

    @Test
    void rawTruncatedResponseAndUsageAreDurableBeforeClientRejectsIt() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger sent = new AtomicInteger();
        server.createContext(
                "/chat/completions",
                e -> {
                    sent.incrementAndGet();
                    byte[] bytes =
                            response()
                                    .body()
                                    .replace("\"stop\"", "\"length\"")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        var client =
                new DeepSeekChatClient(
                        "synthetic",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        ledger,
                        new AiPromptBudget(64000, 8192));
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            assertThatThrownBy(
                            () ->
                                    client.chat(
                                            "system",
                                            List.of(new DeepSeekMessage("user", "question"))))
                    .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        }
        assertThat(sent).hasValue(1);
        assertThat(row()).containsEntry("input_tokens", 20).containsEntry("output_tokens", 5);
        assertThat(jdbc.queryForObject("SELECT response_json FROM ai_call_attempts", String.class))
                .contains("length", "saved answer");
    }

    @Test
    void directEmbeddingTransportPreservesWireContractUsageAndReplay() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger sent = new AtomicInteger();
        var received = new AtomicReference<JSONObject>();
        server.createContext(
                "/services/embeddings/text-embedding/text-embedding",
                e -> {
                    sent.incrementAndGet();
                    received.set(
                            JSON.parseObject(
                                    new String(
                                            e.getRequestBody().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8)));
                    byte[] bytes =
                            JSON.toJSONBytes(
                                    Map.of(
                                            "output",
                                            Map.of(
                                                    "embeddings",
                                                    List.of(
                                                            Map.of(
                                                                    "text_index",
                                                                    0,
                                                                    "embedding",
                                                                    Collections.nCopies(
                                                                            1024, 0.5)))),
                                            "usage",
                                            Map.of("total_tokens", 15)));
                    e.sendResponseHeaders(200, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        var client =
                new AliyunTextEmbeddingClient(
                        "synthetic", "http://127.0.0.1:" + server.getAddress().getPort(), ledger);
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            assertThat(client.embedQuery("定义")).hasSize(1024);
            client.embedQuery("定义");
        }
        assertThat(sent).hasValue(1);
        assertThat(row())
                .containsEntry("retrieval_calls", 1)
                .containsEntry("accounted_tokens", 15L)
                .containsEntry("unknown_calls", 0);
        assertThat(received.get().getJSONObject("parameters").getString("text_type"))
                .isEqualTo("query");
        assertThat(received.get().getJSONObject("input").getJSONArray("texts"))
                .containsExactly("定义");
    }

    @Test
    void responseReceivedButPersistenceFailsKeepsUnknownAndDoesNotReissue() {
        var broken =
                new AiCallLedger(
                        jdbc, manager, runs, tasks, new AiRetryExecutor(rates, d -> {}, () -> 0)) {
                    @Override
                    public void settle(String id, WireResponse response, long elapsed) {
                        throw new IllegalStateException("simulated response persistence crash");
                    }
                };
        long epoch = begin();
        AtomicInteger sent = new AtomicInteger();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            assertThatThrownBy(
                            () ->
                                    broken.exchange(
                                            "synthetic",
                                            "GENERATION",
                                            body(),
                                            () -> {},
                                            null,
                                            d -> {
                                                sent.incrementAndGet();
                                                return response();
                                            }))
                    .isInstanceOf(GovernanceException.class);
        }
        assertThat(sent).hasValue(1);
        assertThat(row()).containsEntry("generation_calls", 1).containsEntry("unknown_calls", 1);
        assertThat(runs.recoverRunning("r", 0)).isFalse();
        assertThat(runs.begin("r", 0)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT response_json FROM ai_call_attempts", String.class))
                .isNull();
    }

    @Test
    void boundedToolProfileIsIndependentAndKnownRejectionsReleaseOnlyTokens() {
        var bounded = PreparedStudyTask.Budget.boundedStudy(55296, 8192, 12);
        assertThat(bounded.generationCalls()).isEqualTo(8);
        assertThat(bounded.maxAccountedTokens()).isEqualTo(96000);
        assertThat(task.budget().generationCalls()).isEqualTo(3);
        long epoch = begin();
        try (var ctx = StudyExecutionContext.open(task, "r", epoch)) {
            ledger.exchange(
                    "synthetic",
                    "GENERATION",
                    body(),
                    () -> {},
                    null,
                    d -> new AiCallLedger.WireResponse(429, "{}", 1));
        } catch (AiProviderException boundedRetries) {
            /* each known rejection is still one real attempt */
        }
        assertThat(row())
                .containsEntry("generation_calls", 3)
                .containsEntry("accounted_tokens", 0L)
                .containsEntry("unknown_calls", 0);
    }

    @Test
    void reservationAdmissionIsAtomicAcrossWorkers() throws Exception {
        jdbc.update("UPDATE video_agent_runs SET status='SUCCEEDED' WHERE id='r'");
        var sync = fixture(10L, 2L, 7L, null, "question", "v1");
        ledger.ensureRequest(sync);
        AtomicInteger allowed = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    try (var ctx = StudyExecutionContext.open(sync, null, 0)) {
                                        try {
                                            String a =
                                                    ledger.reserve(
                                                            "synthetic",
                                                            "GENERATION",
                                                            UUID.randomUUID().toString(),
                                                            100,
                                                            100);
                                            allowed.incrementAndGet();
                                            ledger.settle(a, response(), 1);
                                        } catch (GovernanceException bounded) {
                                        }
                                    }
                                }));
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
        }
        assertThat(allowed.get()).isBetween(1, 3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT generation_calls FROM study_ai_requests WHERE id=?",
                                Integer.class,
                                sync.requestId()))
                .isEqualTo(allowed.get());
    }
}

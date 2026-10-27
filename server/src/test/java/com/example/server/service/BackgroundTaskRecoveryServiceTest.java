package com.example.server.service;

import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException;
import com.example.server.service.ai.AiRateLimitService.AiRateLimitUnavailableException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.Redisson;
import org.redisson.config.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.messaging.Message;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static com.example.server.service.BackgroundTaskRecoveryService.TaskType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackgroundTaskRecoveryServiceTest {
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RocketMQTemplate messages = mock(RocketMQTemplate.class);
    private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-10-26T00:00:00Z"));
    private final Clock clock = mock(Clock.class);
    private JdbcTemplate jdbc;
    private BackgroundTaskRecoveryService service;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        for (var type : BackgroundTaskRecoveryService.TaskType.values()) {
            jdbc.execute("CREATE TABLE " + type.table + " (id VARCHAR(36) PRIMARY KEY, media_id BIGINT, "
                    + "status VARCHAR(24), progress INT DEFAULT 0, retry_count INT DEFAULT 0, "
                    + "error_message VARCHAR(2000), updated_at TIMESTAMP, finished_at TIMESTAMP, deadline_at TIMESTAMP)");
        }
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V16__separate_quota_wait_from_failure_retries.sql")).execute(source);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenAnswer(call -> instant.get());
        var locks = new ConcurrentHashMap<String, RLock>();
        when(redisson.getLock(anyString())).thenAnswer(call -> locks.computeIfAbsent(call.getArgument(0), key -> {
            ReentrantLock local = new ReentrantLock();
            RLock lock = mock(RLock.class);
            try {
                when(lock.tryLock(0, TimeUnit.SECONDS)).thenAnswer(invocation -> local.tryLock());
            } catch (InterruptedException exception) { throw new AssertionError(exception); }
            when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> local.isHeldByCurrentThread());
            doAnswer(invocation -> { local.unlock(); return null; }).when(lock).unlock();
            return lock;
        }));
        SendResult sent = new SendResult();
        sent.setSendStatus(SendStatus.SEND_OK);
        when(messages.syncSend(anyString(), any(Message.class), eq(5000L), anyInt())).thenReturn(sent);
        service = new BackgroundTaskRecoveryService(jdbc, redisson, messages, 30, clock);
    }

    @ParameterizedTest
    @EnumSource(BackgroundTaskRecoveryService.TaskType.class)
    void repeatedQuotaWaitsDoNotConsumeFailureRetriesAndCanResume(BackgroundTaskRecoveryService.TaskType type) {
        insert(type);
        for (long version = 0; version < 6; version++) {
            service.execute(type, "task-1", version, failures -> {
                assertThat(failures).isZero();
                throw limited();
            });
            assertThat(value(type, "status")).isEqualTo("WAITING_FOR_QUOTA");
            assertThat(value(type, "retry_count")).isEqualTo(0);
            assertThat(value(type, "progress")).isEqualTo(70);
            advance(60);
        }
        service.execute(type, "task-1", 6, failures -> succeed(type));
        assertThat(value(type, "status")).isEqualTo("SUCCEEDED");
        assertThat(value(type, "retry_count")).isEqualTo(0);
    }

    @Test
    void preservesRealFailuresAcrossQuotaWaitsAndRejectsOldMessages() {
        insert(ANALYSIS);
        service.execute(ANALYSIS, "task-1", 0, failures -> { throw transientFailure(); });
        advance(10);
        service.execute(ANALYSIS, "task-1", 1, failures -> { throw limited(); });
        advance(60);
        AtomicInteger attempts = new AtomicInteger();
        service.execute(ANALYSIS, "task-1", 0, failures -> attempts.incrementAndGet());
        assertThat(attempts).hasValue(0);
        service.execute(ANALYSIS, "task-1", 2, failures -> {
            assertThat(failures).isEqualTo(1);
            throw transientFailure();
        });
        assertThat(value(ANALYSIS, "retry_count")).isEqualTo(2);
        assertThat(value(ANALYSIS, "dispatch_version")).isEqualTo(3L);
        advance(30);
        service.execute(ANALYSIS, "task-1", 3, failures -> { throw transientFailure(); });
        advance(60);
        service.execute(ANALYSIS, "task-1", 4, failures -> { throw transientFailure(); });
        assertThat(value(ANALYSIS, "status")).isEqualTo("FAILED");
        assertThat(value(ANALYSIS, "retry_count")).isEqualTo(3);
    }

    @Test
    void promptBudgetErrorsAreTerminalAndDoNotSchedulePaidFailureRetries() {
        insert(AGENT);
        service.execute(AGENT, "task-1", 0, failures -> {
            throw new com.example.server.service.ai.AiPromptBudget.PromptBudgetExceededException("input too large");
        });
        assertThat(value(AGENT, "status")).isEqualTo("FAILED");
        assertThat(value(AGENT, "retry_count")).isEqualTo(0);
        assertThat(value(AGENT, "error_message")).isEqualTo("input too large");
        verifyNoInteractions(messages);
    }

    @Test
    void permanentProviderErrorsFailImmediatelyButRedisOutagesAreFailures() {
        insert(AGENT);
        service.execute(AGENT, "task-1", 0, failures -> {
            throw new AiProviderException("invalid key", null, false, 0);
        });
        assertThat(value(AGENT, "status")).isEqualTo("FAILED");
        verifyNoInteractions(messages);
        insert(ANALYSIS);
        service.execute(ANALYSIS, "task-1", 0, failures -> {
            throw new AiRateLimitUnavailableException("Redis unavailable", null);
        });
        assertThat(value(ANALYSIS, "status")).isEqualTo("RETRYING");
        assertThat(value(ANALYSIS, "retry_count")).isEqualTo(1);
        assertThat(value(ANALYSIS, "quota_wait_started_at")).isNull();
    }

    @ParameterizedTest
    @EnumSource(BackgroundTaskRecoveryService.TaskType.class)
    void serializesWakeupBodyVersionAndDelayThroughTheRealRocketMqTemplate(
            BackgroundTaskRecoveryService.TaskType type) throws Exception {
        var producer = mock(org.apache.rocketmq.client.producer.DefaultMQProducer.class);
        SendResult sent = new SendResult();
        sent.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(org.apache.rocketmq.common.message.Message.class), eq(5000L))).thenReturn(sent);
        var template = new RocketMQTemplate();
        template.setProducer(producer);
        template.setMessageConverter(new org.apache.rocketmq.spring.support.RocketMQMessageConverter().getMessageConverter());
        var actual = new BackgroundTaskRecoveryService(jdbc, redisson, template, 30, clock);
        insert(type);

        actual.execute(type, "task-1", 0, failures -> { throw limited(); });

        var message = org.mockito.ArgumentCaptor.forClass(org.apache.rocketmq.common.message.Message.class);
        verify(producer).send(message.capture(), eq(5000L));
        assertThat(message.getValue().getUserProperty(BackgroundTaskRecoveryService.VERSION_HEADER)).isEqualTo("1");
        assertThat(message.getValue().getDelayTimeLevel()).isEqualTo(5);
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(message.getValue().getBody());
        assertThat(body.get(type.idField).asText()).isEqualTo("task-1");
        assertThat(body.get("mediaId").asLong()).isEqualTo(42L);
    }

    @Test
    void lockInfrastructureFailureSchedulesRecoveryWithoutRunningPaidWork() {
        insert(ANALYSIS);
        when(redisson.getLock(anyString())).thenThrow(new IllegalStateException("Redis down"));
        service.execute(ANALYSIS, "task-1", 0, failures -> fail("Paid operation bypassed Redis protection"));
        service.execute(ANALYSIS, "task-1", 0, failures -> fail("Stale delivery ran"));
        assertThat(value(ANALYSIS, "status")).isEqualTo("RETRYING");
        assertThat(value(ANALYSIS, "retry_count")).isEqualTo(1);
        assertThat(value(ANALYSIS, "dispatch_version")).isEqualTo(1L);
    }

    @Test
    void recoversFailedPublicationAfterRestartWithoutResettingFailureCount() {
        insert(ANALYSIS);
        when(messages.syncSend(anyString(), any(Message.class), eq(5000L), anyInt()))
                .thenThrow(new IllegalStateException("broker down"));
        service.execute(ANALYSIS, "task-1", 0, failures -> { throw limited(); });
        assertThat(value(ANALYSIS, "status")).isEqualTo("WAITING_FOR_QUOTA");
        advance(91);
        var restarted = new BackgroundTaskRecoveryService(jdbc, redisson, messages, 30, clock);
        restarted.recoverDueTasks();
        verify(messages).syncSend(eq("video-analysis-topic"), argThat((Message<?> message) ->
                "1".equals(message.getHeaders().get(BackgroundTaskRecoveryService.VERSION_HEADER))), eq(5000L), eq(0));
        assertThat(value(ANALYSIS, "retry_count")).isEqualTo(0);
    }

    @Test
    void compensatesCrashBetweenDatabaseCommitAndSendAndThrottlesDuplicateScans() {
        insert(ANALYSIS);
        jdbc.update("UPDATE analysis_jobs SET status = 'WAITING_FOR_QUOTA', dispatch_version = 4, "
                + "next_attempt_at = ?, updated_at = ?", now().minusSeconds(40), now().minusSeconds(100));
        service.recoverDueTasks();
        service.recoverDueTasks();
        verify(messages, times(1)).syncSend(anyString(), any(Message.class), eq(5000L), eq(0));
        service.execute(ANALYSIS, "task-1", 4, failures -> succeed(ANALYSIS));
        advance(100);
        service.recoverDueTasks();
        service.execute(ANALYSIS, "task-1", 4, failures -> fail("Terminal task was executed"));
        verify(messages, times(1)).syncSend(anyString(), any(Message.class), eq(5000L), eq(0));
    }

    @Test
    void ignoresEarlyDeliveryAndKeepsThePendingWakeupRecoverable() {
        insert(ANALYSIS);
        service.execute(ANALYSIS, "task-1", 0, failures -> { throw limited(); });
        service.execute(ANALYSIS, "task-1", 1, failures -> fail("Ran before due time"));
        assertThat(value(ANALYSIS, "next_attempt_at")).isNotNull();
        advance(91);
        service.recoverDueTasks();
        verify(messages).syncSend(anyString(), any(Message.class), eq(5000L), eq(0));
    }

    @Test
    void expiresAnalysisQuotaWaitAndPreservesAgentDeadline() {
        insert(ANALYSIS);
        service.execute(ANALYSIS, "task-1", 0, failures -> { throw limited(); });
        advance(1800);
        service.execute(ANALYSIS, "task-1", 1, failures -> fail("Wait deadline was ignored"));
        assertThat(value(ANALYSIS, "status")).isEqualTo("FAILED");
        assertThat(value(ANALYSIS, "error_message").toString()).contains("额度超时");
        insert(AGENT);
        LocalDateTime deadline = now().plusMinutes(10);
        jdbc.update("UPDATE video_agent_runs SET deadline_at = ?", deadline);
        service.execute(AGENT, "task-1", 0, failures -> { throw limited(); });
        advance(600);
        service.recoverDueTasks();
        assertThat(value(AGENT, "status")).isEqualTo("FAILED");
        assertThat(value(AGENT, "deadline_at")).isEqualTo(java.sql.Timestamp.valueOf(deadline));
        assertThat(value(AGENT, "retry_count")).isEqualTo(0);
    }

    @Test
    void concurrentDuplicateConsumersRunOnlyOneOperation() throws Exception {
        insert(ANALYSIS);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> service.execute(ANALYSIS, "task-1", 0, failures -> {
                attempts.incrementAndGet();
                entered.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { throw new AssertionError(exception); }
                succeed(ANALYSIS);
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            threads.submit(() -> service.execute(ANALYSIS, "task-1", 0,
                    failures -> attempts.incrementAndGet())).get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(attempts).hasValue(1);
    }

    @Test
    void sharesTheExecutionLockAcrossTwoRealRedisClients(@TempDir Path directory) throws Exception {
        Path executable = java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                .map(Path::of).map(path -> path.resolve("redis-server"))
                .filter(Files::isExecutable).findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null, "redis-server is required for this integration check");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Process process = new ProcessBuilder(executable.toString(), "--port", Integer.toString(port),
                "--bind", "127.0.0.1", "--save", "", "--appendonly", "no")
                .redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        RedissonClient firstClient = null;
        RedissonClient secondClient = null;
        try {
            boolean ready = false;
            for (int attempt = 0; attempt < 100; attempt++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                    ready = true;
                    break;
                } catch (java.io.IOException exception) { Thread.sleep(20); }
            }
            assertThat(ready).as("temporary Redis server started").isTrue();
            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:" + port)
                    .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
            firstClient = Redisson.create(config);
            secondClient = Redisson.create(config);
            var firstService = new BackgroundTaskRecoveryService(jdbc, firstClient, messages, 30, clock);
            var secondService = new BackgroundTaskRecoveryService(jdbc, secondClient, messages, 30, clock);
            insert(ANALYSIS);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var first = threads.submit(() -> firstService.execute(ANALYSIS, "task-1", 0, failures -> {
                    entered.countDown();
                    try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException exception) { throw new AssertionError(exception); }
                    throw limited();
                }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    threads.submit(() -> secondService.execute(ANALYSIS, "task-1", 0,
                            failures -> fail("Duplicate operation acquired the distributed lock"))).get(5, TimeUnit.SECONDS);
                } finally { release.countDown(); }
                first.get(5, TimeUnit.SECONDS);
            }
            advance(60);
            secondService.execute(ANALYSIS, "task-1", 1, failures -> succeed(ANALYSIS));
            assertThat(value(ANALYSIS, "status")).isEqualTo("SUCCEEDED");
            assertThat(value(ANALYSIS, "retry_count")).isEqualTo(0);
        } finally {
            if (secondClient != null) secondClient.shutdown();
            if (firstClient != null) firstClient.shutdown();
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
    }

    private void insert(BackgroundTaskRecoveryService.TaskType type) {
        jdbc.update("INSERT INTO " + type.table + " (id, media_id, status, progress, updated_at) VALUES (?, ?, ?, ?, ?)",
                "task-1", 42L, "QUEUED", 70, now());
    }

    private Object value(BackgroundTaskRecoveryService.TaskType type, String column) {
        return jdbc.queryForMap("SELECT * FROM " + type.table + " WHERE id = 'task-1'").get(column);
    }

    private void succeed(BackgroundTaskRecoveryService.TaskType type) {
        jdbc.update("UPDATE " + type.table + " SET status = 'SUCCEEDED' WHERE id = 'task-1'");
    }

    private AiRateLimitExceededException limited() { return new AiRateLimitExceededException("busy", 60); }
    private AiProviderException transientFailure() { return new AiProviderException("network failure", null, true, 0); }
    private void advance(long seconds) { instant.updateAndGet(value -> value.plusSeconds(seconds)); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
}

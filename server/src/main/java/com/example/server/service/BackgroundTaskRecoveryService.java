package com.example.server.service;

import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Durable quota waits and business retries; broker redelivery counts are transport details. */
@Service
@EnableScheduling
public class BackgroundTaskRecoveryService {
    public static final String VERSION_HEADER = "task_dispatch_version";
    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskRecoveryService.class);
    private static final int MAX_FAILURE_RETRIES = 3;
    private static final long[] DELAY_SECONDS = {
            1, 5, 10, 30, 60, 120, 180, 240, 300, 360, 420, 480, 540, 600, 1200, 1800, 3600, 7200
    };

    public enum TaskType {
        ANALYSIS("analysis_jobs", "video-analysis-topic", "jobId", "lock:analysis-job:"),
        AGENT("video_agent_runs", "video-agent-topic", "runId", "lock:video-agent-run:");

        final String table;
        final String topic;
        final String idField;
        final String lockPrefix;

        TaskType(String table, String topic, String idField, String lockPrefix) {
            this.table = table;
            this.topic = topic;
            this.idField = idField;
            this.lockPrefix = lockPrefix;
        }
    }

    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;
    private final RocketMQTemplate messages;
    private final long analysisWaitMinutes;
    private final Clock clock;

    @Autowired
    public BackgroundTaskRecoveryService(
            JdbcTemplate jdbc, RedissonClient redisson, RocketMQTemplate messages,
            @Value("${ai.task-recovery.analysis-quota-wait-minutes:30}") long analysisWaitMinutes
    ) {
        this(jdbc, redisson, messages, analysisWaitMinutes, Clock.systemDefaultZone());
    }

    BackgroundTaskRecoveryService(JdbcTemplate jdbc, RedissonClient redisson,
                                  RocketMQTemplate messages, long analysisWaitMinutes, Clock clock) {
        if (analysisWaitMinutes <= 0) throw new IllegalArgumentException("Quota wait limit must be positive");
        this.jdbc = jdbc;
        this.redisson = redisson;
        this.messages = messages;
        this.analysisWaitMinutes = analysisWaitMinutes;
        this.clock = clock;
    }

    public void execute(TaskType type, String id, long version, IntConsumer operation) {
        RLock lock = null;
        boolean locked = false;
        try {
            try {
                lock = redisson.getLock(type.lockPrefix + id);
                locked = lock.tryLock(0, TimeUnit.SECONDS);
            } catch (RuntimeException exception) {
                deferLockFailure(type, id, version, exception);
                return;
            }
            if (!locked) return;
            Task task = load(type, id);
            LocalDateTime now = now();
            if (task == null || task.terminal() || task.version != version
                    || (task.nextAttempt != null && task.nextAttempt.isAfter(now))) return;
            if (expire(type, task, now)) return;
            int claimed = jdbc.update("UPDATE " + type.table + " SET status = ?, next_attempt_at = NULL, updated_at = ? "
                            + "WHERE id = ? AND dispatch_version = ? AND status NOT IN ('SUCCEEDED', 'FAILED')",
                    type == TaskType.ANALYSIS ? "PREPARING" : "PLANNING", now, id, version);
            if (claimed != 1) return;
            try {
                operation.accept(task.retries);
            } catch (AiRateLimitExceededException exception) {
                defer(type, task, exception, true, false);
            } catch (RuntimeException exception) {
                if ((exception instanceof AiProviderException provider && !provider.isRetryable())
                        || exception instanceof IllegalArgumentException
                        || task.retries >= MAX_FAILURE_RETRIES) {
                    fail(type, task, exception.getMessage(), false);
                } else {
                    defer(type, task, exception, false, false);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring task lock", exception);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void deferLockFailure(TaskType type, String id, long version, RuntimeException error) {
        Task task = load(type, id);
        if (task == null || task.terminal() || task.version != version
                || task.nextAttempt != null && task.nextAttempt.isAfter(now())) return;
        // Without the Redis lock, only a pending task may be claimed through the database CAS.
        if (!task.pending()) throw error;
        if (task.retries >= MAX_FAILURE_RETRIES) {
            fail(type, task, error.getMessage(), true);
        } else {
            defer(type, task, error, false, true);
        }
    }

    private void defer(TaskType type, Task task, RuntimeException error, boolean quotaWait, boolean pendingOnly) {
        LocalDateTime now = now();
        LocalDateTime waitStarted = quotaWait && task.waitStarted == null ? now : task.waitStarted;
        Task waiting = new Task(task.id, task.mediaId, quotaWait ? "WAITING_FOR_QUOTA" : "RETRYING",
                task.retries, task.version, null, waitStarted, task.deadline);
        if (expire(type, waiting, now)) return;
        int retries = quotaWait ? task.retries : task.retries + 1;
        long seconds = quotaWait ? Math.max(1, ((AiRateLimitExceededException) error).retryAfterSeconds())
                : new long[]{10, 30, 60}[retries - 1];
        LocalDateTime nextAttempt = now.plusSeconds(seconds);
        int changed = jdbc.update("UPDATE " + type.table + " SET status = ?, retry_count = ?, "
                        + "dispatch_version = dispatch_version + 1, next_attempt_at = ?, quota_wait_started_at = ?, "
                        + "error_message = ?, updated_at = ? WHERE id = ? AND dispatch_version = ? "
                        + "AND status NOT IN ('SUCCEEDED', 'FAILED')"
                        + (pendingOnly ? " AND status IN ('QUEUED', 'WAITING_FOR_QUOTA', 'RETRYING')" : ""),
                waiting.status, retries, nextAttempt, waitStarted,
                quotaWait ? null : concise(error.getMessage()), now, task.id, task.version);
        if (changed == 1) publish(type, task.id, task.mediaId, task.version + 1, seconds);
    }

    /** Repair a failed/uncertain send or a crash after committing the pending task state. */
    @Scheduled(fixedDelayString = "${ai.task-recovery.scan-interval-ms:10000}")
    public void recoverDueTasks() {
        LocalDateTime cutoff = now().minusSeconds(30);
        for (TaskType type : TaskType.values()) {
            for (Task task : jdbc.query(select(type) + " WHERE status IN ('WAITING_FOR_QUOTA', 'RETRYING') "
                    + "AND next_attempt_at <= ? AND updated_at <= ? ORDER BY next_attempt_at LIMIT 100",
                    (rs, row) -> task(rs), cutoff, cutoff)) {
                recover(type, task);
            }
        }
    }

    private void recover(TaskType type, Task candidate) {
        RLock lock = redisson.getLock(type.lockPrefix + candidate.id);
        boolean locked = false;
        Task wakeup = null;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);
            if (!locked) return;
            Task task = load(type, candidate.id);
            if (task == null || task.terminal() || task.version != candidate.version) return;
            LocalDateTime now = now();
            if (expire(type, task, now)) return;
            // Claim only publication; keep the due time so an immediate delivery can run.
            int claimed = jdbc.update("UPDATE " + type.table + " SET updated_at = ? WHERE id = ? "
                            + "AND dispatch_version = ? AND status IN ('WAITING_FOR_QUOTA', 'RETRYING') "
                            + "AND next_attempt_at <= ? AND updated_at <= ?",
                    now, task.id, task.version, now.minusSeconds(30), now.minusSeconds(30));
            if (claimed == 1) wakeup = task;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException exception) {
            log.warn("Unable to recover {} task {}", type, candidate.id, exception);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
        if (wakeup != null) publish(type, wakeup.id, wakeup.mediaId, wakeup.version, 0);
    }

    private void publish(TaskType type, String id, long mediaId, long version, long delaySeconds) {
        int delayLevel = 0;
        if (delaySeconds > 0) {
            while (delayLevel < DELAY_SECONDS.length - 1 && DELAY_SECONDS[delayLevel] < delaySeconds) delayLevel++;
            delayLevel++;
        }
        try {
            var message = MessageBuilder.withPayload(Map.of(type.idField, id, "mediaId", mediaId))
                    .setHeader(VERSION_HEADER, Long.toString(version)).build();
            var result = messages.syncSend(type.topic, message, 5_000, delayLevel);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("Task wakeup was not acknowledged by the broker");
            }
        } catch (RuntimeException exception) {
            // The pending database row remains recoverable; acknowledging this delivery is safe.
            log.warn("Unable to publish {} task {} version {}; recovery scan will retry", type, id, version, exception);
        }
    }

    private boolean expire(TaskType type, Task task, LocalDateTime now) {
        boolean quotaExpired = task.waitStarted != null && (type == TaskType.ANALYSIS
                ? !now.isBefore(task.waitStarted.plusMinutes(analysisWaitMinutes))
                : task.deadline != null && !now.isBefore(task.deadline));
        if (quotaExpired || task.deadline != null && !now.isBefore(task.deadline)) {
            fail(type, task, quotaExpired ? "等待 AI 服务额度超时，请稍后重新提交" : "Agent run exceeded its 10 minute deadline", false);
            return true;
        }
        return false;
    }

    private void fail(TaskType type, Task task, String message, boolean pendingOnly) {
        jdbc.update("UPDATE " + type.table + " SET status = 'FAILED', error_message = ?, "
                        + "next_attempt_at = NULL, finished_at = ?, updated_at = ? "
                        + "WHERE id = ? AND dispatch_version = ? AND status NOT IN ('SUCCEEDED', 'FAILED')"
                        + (pendingOnly ? " AND status IN ('QUEUED', 'WAITING_FOR_QUOTA', 'RETRYING')" : ""),
                concise(message), now(), now(), task.id, task.version);
    }

    private Task load(TaskType type, String id) {
        return jdbc.query(select(type) + " WHERE id = ?", (rs, row) -> task(rs), id).stream().findFirst().orElse(null);
    }

    private String select(TaskType type) {
        return "SELECT id, media_id, status, retry_count, dispatch_version, next_attempt_at, quota_wait_started_at, "
                + (type == TaskType.AGENT ? "deadline_at" : "NULL AS deadline_at") + " FROM " + type.table;
    }

    private Task task(ResultSet rs) throws SQLException {
        return new Task(rs.getString("id"), rs.getLong("media_id"), rs.getString("status"),
                rs.getInt("retry_count"), rs.getLong("dispatch_version"), date(rs, "next_attempt_at"),
                date(rs, "quota_wait_started_at"), date(rs, "deadline_at"));
    }

    private LocalDateTime date(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    private String concise(String message) {
        return message == null ? "Unknown task failure" : message.substring(0, Math.min(2000, message.length()));
    }

    private record Task(String id, long mediaId, String status, int retries, long version,
                        LocalDateTime nextAttempt, LocalDateTime waitStarted, LocalDateTime deadline) {
        boolean terminal() { return "SUCCEEDED".equals(status) || "FAILED".equals(status); }
        boolean pending() { return "QUEUED".equals(status) || "WAITING_FOR_QUOTA".equals(status) || "RETRYING".equals(status); }
    }
}

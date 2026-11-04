package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.entity.StudyRunStatus;
import com.example.server.service.ai.StudyExecutionContext;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Database leases fence workers; Redis locks only avoid duplicate work. No network calls in these
 * transactions.
 */
@Service
public class StudyRunControl {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StudyTaskPreparationService tasks;
    private final Clock clock;

    @Autowired
    public StudyRunControl(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            StudyTaskPreparationService tasks) {
        this(jdbc, manager, tasks, Clock.systemDefaultZone());
    }

    public StudyRunControl(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            StudyTaskPreparationService tasks,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tasks = tasks;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public Map<String, Object> row(String id, boolean lock) {
        return jdbc
                .queryForList(
                        "SELECT * FROM video_agent_runs WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Study run not found"));
    }

    public PreparedStudyTask task(Map<String, Object> row) {
        String json = (String) row.get("prepared_task");
        if (json == null) throw new GovernanceException("TASK_RESOURCE_CHANGED", "任务没有冻结快照，请重新提交");
        return JSON.parseObject(json, PreparedStudyTask.class);
    }

    /**
     * A claimed delivery is RUNNING, but only this transaction starts its active clock and grants a
     * lease.
     */
    public OptionalLong begin(String id, long dispatchVersion) {
        var snapshot = row(id, false);
        if (StudyRunStatus.terminal((String) snapshot.get("status"))
                || number(snapshot, "dispatch_version") != dispatchVersion)
            return OptionalLong.empty();
        tasks.assertCurrent(task(snapshot));
        Long epoch =
                tx.execute(
                        s -> {
                            var r = row(id, true);
                            if (StudyRunStatus.terminal((String) r.get("status"))
                                    || number(r, "dispatch_version") != dispatchVersion
                                    || date(r, "active_started_at") != null) return null;
                            if (unresolved(id)) {
                                stopLocked(r, "MODEL_OUTCOME_UNKNOWN", "上次供应商调用结果未知，已停止自动重投", null);
                                return null;
                            }
                            if (expired(r)) return null;
                            var now = now();
                            long waiting =
                                    number(r, "waiting_ms")
                                            + elapsed(date(r, "wait_started_at"), now);
                            long next = number(r, "execution_epoch") + 1;
                            // A stale running worker is charged conservatively through recovery,
                            // never granted a fresh time budget.
                            long remaining = remaining(r, now);
                            if (remaining <= 0) {
                                stopLocked(r, "TASK_DEADLINE_EXCEEDED", "活动时间预算已耗尽", null);
                                return null;
                            }
                            jdbc.update(
                                    "UPDATE video_agent_runs SET"
                                        + " status='RUNNING',execution_epoch=?,active_remaining_ms=?,active_started_at=?,wait_started_at=NULL,waiting_ms=?,started_at=COALESCE(started_at,?),next_attempt_at=NULL,updated_at=?"
                                        + " WHERE id=?",
                                    next,
                                    remaining,
                                    now,
                                    waiting,
                                    now,
                                    now,
                                    id);
                            eventLocked(
                                    id,
                                    next,
                                    "EXECUTION_STARTED",
                                    Map.of(
                                            "retryCount",
                                            number(r, "retry_count"),
                                            "activeRemainingMs",
                                            remaining));
                            return next;
                        });
        return epoch == null ? OptionalLong.empty() : OptionalLong.of(epoch);
    }

    public void assertLease(String id) {
        var r = row(id, false);
        requireLease(r);
        tasks.assertCurrent(task(r));
    }

    /**
     * Caller owns an existing result/step transaction; the row lock survives until that transaction
     * commits.
     */
    public Map<String, Object> lockLease(String id) {
        var r = row(id, true);
        requireLease(r);
        return r;
    }

    private void requireLease(Map<String, Object> r) {
        String id = (String) r.get("id");
        if (number(r, "execution_epoch") != StudyExecutionContext.epoch(id)
                || !"RUNNING".equals(r.get("status")))
            throw new GovernanceException("STALE_EXECUTION", "任务已停止或执行代次已变化");
        if (!now().isBefore(date(r, "deadline_at")) || remaining(r, now()) <= 0)
            throw new GovernanceException("TASK_DEADLINE_EXCEEDED", "活动时间或总截止已到达");
    }

    public Instant deadline(String id) {
        assertLease(id);
        var r = row(id, false);
        var now = now();
        var active = now.plusNanos(Math.max(0, remaining(r, now) - 2000) * 1_000_000);
        var wall = date(r, "deadline_at").minusSeconds(2);
        return (active.isBefore(wall) ? active : wall).atZone(clock.getZone()).toInstant();
    }

    public void stage(String id, String phase, int progress) {
        assertLease(id);
        tx.executeWithoutResult(
                s -> {
                    var r = lockLease(id);
                    jdbc.update(
                            "UPDATE video_agent_runs SET phase=?,progress=?,updated_at=? WHERE"
                                + " id=?",
                            phase,
                            progress,
                            now(),
                            id);
                    eventLocked(
                            id,
                            number(r, "execution_epoch"),
                            "PHASE_CHANGED",
                            Map.of("phase", phase, "progress", progress));
                });
    }

    public boolean pause(
            String id,
            long version,
            boolean quota,
            long delaySeconds,
            String reason,
            boolean pendingOnly) {
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            var r = row(id, true);
                            if (StudyRunStatus.terminal((String) r.get("status"))
                                    || number(r, "dispatch_version") != version
                                    || pendingOnly
                                            && !List.of("QUEUED", "WAITING_FOR_QUOTA", "RETRY_WAIT")
                                                    .contains(r.get("status"))) return false;
                            if (unresolved(id)) {
                                stopLocked(r, "MODEL_OUTCOME_UNKNOWN", "供应商调用结果未知，已停止自动重投", null);
                                return false;
                            }
                            if (expired(r)) return false;
                            long left = remaining(r, now());
                            if (left <= 0) {
                                stopLocked(r, "TASK_DEADLINE_EXCEEDED", "活动时间预算已耗尽", null);
                                return false;
                            }
                            int retries = (int) number(r, "retry_count") + (quota ? 0 : 1);
                            var now = now();
                            jdbc.update(
                                    "UPDATE video_agent_runs SET"
                                        + " status=?,retry_count=?,dispatch_version=dispatch_version+1,execution_epoch=execution_epoch+1,active_remaining_ms=?,active_started_at=NULL,wait_started_at=?,next_attempt_at=?,quota_wait_started_at=CASE"
                                        + " WHEN ? THEN COALESCE(quota_wait_started_at,?) ELSE"
                                        + " quota_wait_started_at END,error_message=?,updated_at=?"
                                        + " WHERE id=?",
                                    quota ? "WAITING_FOR_QUOTA" : "RETRY_WAIT",
                                    retries,
                                    left,
                                    now,
                                    now.plusSeconds(delaySeconds),
                                    quota,
                                    now,
                                    quota ? null : concise(reason),
                                    now,
                                    id);
                            eventLocked(
                                    id,
                                    number(r, "execution_epoch") + 1,
                                    quota ? "QUOTA_WAIT" : "RETRY_SCHEDULED",
                                    Map.of(
                                            "retryCount",
                                            retries,
                                            "activeRemainingMs",
                                            left,
                                            "delaySeconds",
                                            delaySeconds));
                            return true;
                        }));
    }

    /**
     * Recovery is allowed only after acquiring the task lock. Unsettled network calls end the run
     * conservatively.
     */
    public boolean recoverRunning(String id, long version) {
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            var r = row(id, true);
                            if (!"RUNNING".equals(r.get("status"))
                                    || number(r, "dispatch_version") != version) return false;
                            if (unresolved(id)) {
                                stopLocked(r, "MODEL_OUTCOME_UNKNOWN", "执行中断且远端结果未知；已保留用量预留", null);
                                return false;
                            }
                            if (number(r, "retry_count") >= 3) {
                                stopLocked(r, "TASK_RETRY_EXHAUSTED", "执行中断恢复次数已耗尽", null);
                                return false;
                            }
                            return pause(id, version, false, 0, "执行中断，从已保存的响应与检查点恢复", false);
                        }));
    }

    public void stop(
            String id, String code, String reason, String requestedStatus, boolean requireEpoch) {
        tx.executeWithoutResult(
                s -> {
                    var r = row(id, true);
                    if (StudyRunStatus.terminal((String) r.get("status"))) return;
                    if (requireEpoch
                            && number(r, "execution_epoch") != StudyExecutionContext.epoch(id))
                        return;
                    stopLocked(r, code, reason, requestedStatus);
                });
    }

    public void cancel(String id, Long learnerId) {
        tx.executeWithoutResult(
                s -> {
                    var r = row(id, true);
                    if (!Objects.equals(number(r, "learner_id"), learnerId))
                        throw new NoSuchElementException("Study run not found");
                    if (!StudyRunStatus.terminal((String) r.get("status")))
                        stopLocked(r, "TASK_CANCELLED", "用户已取消任务", "CANCELLED");
                });
    }

    public boolean expireTask(String id, long version) {
        return Boolean.TRUE.equals(
                tx.execute(
                        s -> {
                            var r = row(id, true);
                            return number(r, "dispatch_version") != version || expired(r);
                        }));
    }

    public void stopIfVersion(
            String id, long version, String code, String reason, boolean pendingOnly) {
        tx.executeWithoutResult(
                s -> {
                    var r = row(id, true);
                    if (number(r, "dispatch_version") != version
                            || StudyRunStatus.terminal((String) r.get("status"))
                            || pendingOnly
                                    && !List.of("QUEUED", "WAITING_FOR_QUOTA", "RETRY_WAIT")
                                            .contains(r.get("status"))) return;
                    stopLocked(r, code, reason, null);
                });
    }

    public void expireDue() {
        for (String id :
                jdbc.queryForList(
                        "SELECT id FROM video_agent_runs WHERE status NOT IN "
                                + StudyRunStatus.TERMINAL_SQL
                                + " AND (deadline_at<=? OR (active_started_at IS NOT NULL AND"
                                + " TIMESTAMPDIFF(MICROSECOND,active_started_at,?)/1000>=active_remaining_ms))"
                                + " LIMIT 100",
                        String.class,
                        now(),
                        now())) {
            tx.executeWithoutResult(
                    s -> {
                        var r = row(id, true);
                        expired(r);
                    });
        }
    }

    private boolean expired(Map<String, Object> r) {
        if (StudyRunStatus.terminal((String) r.get("status"))) return true;
        if (!now().isBefore(date(r, "deadline_at")) || remaining(r, now()) <= 0) {
            stopLocked(r, "TASK_DEADLINE_EXCEEDED", "活动时间或总截止已到达", null);
            return true;
        }
        return false;
    }

    private boolean unresolved(String id) {
        int count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ai_call_attempts WHERE run_id=? AND status IN"
                            + " ('RESERVED','IN_FLIGHT','UNKNOWN')",
                        Integer.class,
                        id);
        if (count == 0) return false;
        jdbc.update(
                "UPDATE ai_call_attempts SET"
                    + " status='UNKNOWN',error_code='MODEL_OUTCOME_UNKNOWN',finished_at=COALESCE(finished_at,?)"
                    + " WHERE run_id=? AND status IN ('RESERVED','IN_FLIGHT')",
                now(),
                id);
        return true;
    }

    private void stopLocked(Map<String, Object> r, String code, String reason, String status) {
        String id = (String) r.get("id");
        var now = now();
        String terminal =
                status == null ? (r.get("result_json") == null ? "FAILED" : "PARTIAL") : status;
        jdbc.update(
                "UPDATE video_agent_runs SET"
                    + " status=?,stop_code=?,error_message=?,execution_epoch=execution_epoch+1,dispatch_version=dispatch_version+1,active_remaining_ms=?,active_started_at=NULL,waiting_ms=?,wait_started_at=NULL,progress=100,next_attempt_at=NULL,finished_at=?,updated_at=?"
                    + " WHERE id=?",
                terminal,
                code,
                concise(reason),
                remaining(r, now),
                number(r, "waiting_ms") + elapsed(date(r, "wait_started_at"), now),
                now,
                now,
                id);
        eventLocked(
                id,
                number(r, "execution_epoch") + 1,
                "TASK_STOPPED",
                Map.of("status", terminal, "code", code));
    }

    public void completeLocked(Map<String, Object> r) {
        String id = (String) r.get("id");
        var now = now();
        jdbc.update(
                "UPDATE video_agent_runs SET"
                    + " status='SUCCEEDED',stop_code=NULL,error_message=NULL,progress=100,active_remaining_ms=?,active_started_at=NULL,next_attempt_at=NULL,finished_at=?,updated_at=?"
                    + " WHERE id=?",
                remaining(r, now),
                now,
                now,
                id);
        eventLocked(
                id, number(r, "execution_epoch"), "TASK_COMPLETED", Map.of("status", "SUCCEEDED"));
    }

    /**
     * Only whitelisted metadata reaches the student event endpoint. Never pass model text, prompts
     * or raw responses.
     */
    public void eventLocked(String id, long epoch, String type, Map<String, ?> payload) {
        new StudyRunEvents(jdbc).append(id, epoch, type, payload, now());
    }

    public List<Map<String, Object>> events(String id, Long learner, long after) {
        var r = row(id, false);
        if (!Objects.equals(number(r, "learner_id"), learner))
            throw new NoSuchElementException("Study run not found");
        if (after < 0) throw new IllegalArgumentException("Invalid event cursor");
        return jdbc.query(
                "SELECT sequence_no,event_type,payload,created_at FROM study_run_events WHERE"
                    + " run_id=? AND sequence_no>? ORDER BY sequence_no LIMIT 100",
                (rs, n) ->
                        Map.of(
                                "sequence",
                                rs.getLong(1),
                                "type",
                                rs.getString(2),
                                "data",
                                JSON.parseObject(rs.getString(3)),
                                "createdAt",
                                rs.getTimestamp(4).toLocalDateTime()),
                id,
                after);
    }

    public static long number(Map<String, Object> row, String key) {
        var n = row.get(key);
        return n == null ? 0 : ((Number) n).longValue();
    }

    public static LocalDateTime date(Map<String, Object> row, String key) {
        var d = row.get(key);
        return d == null
                ? null
                : d instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) d;
    }

    private long elapsed(LocalDateTime from, LocalDateTime to) {
        return from == null ? 0 : Math.max(0, Duration.between(from, to).toMillis());
    }

    private long remaining(Map<String, Object> r, LocalDateTime now) {
        return Math.max(
                0, number(r, "active_remaining_ms") - elapsed(date(r, "active_started_at"), now));
    }

    private String concise(String s) {
        return s == null ? "任务停止" : s.substring(0, Math.min(2000, s.length()));
    }
}

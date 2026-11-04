package com.example.server.service.ai;

import static com.example.server.service.StudyRunControl.*;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.service.StudyRunControl;
import com.example.server.service.StudyTaskPreparationService;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import okhttp3.Call;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One committed reservation per actual transport attempt. Provider calls never run inside a DB
 * transaction.
 */
@Service
public class AiCallLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate durable;
    private final StudyRunControl runs;
    private final StudyTaskPreparationService tasks;
    private final AiRetryExecutor retries;
    private final Map<String, Set<Call>> calls = new ConcurrentHashMap<>();

    public AiCallLedger(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            StudyRunControl runs,
            StudyTaskPreparationService tasks,
            AiRetryExecutor retries) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.tasks = tasks;
        this.retries = retries;
        durable = new TransactionTemplate(manager);
        durable.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public StudyExecutionContext open(PreparedStudyTask task) {
        ensureRequest(task);
        return StudyExecutionContext.open(task, null, 0);
    }

    public void ensureRequest(PreparedStudyTask task) {
        var b = task.budget();
        durable.executeWithoutResult(
                s -> {
                    jdbc.update(
                            "INSERT INTO"
                                + " study_ai_requests(id,learner_id,profile,max_generation_calls,max_retrieval_calls,max_accounted_tokens,created_at,deadline_at)"
                                + " VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                            task.requestId(),
                            task.learnerId(),
                            task.executionStrategy(),
                            b.generationCalls(),
                            b.retrievalCalls(),
                            b.maxAccountedTokens(),
                            runs.now(),
                            runs.now().plusNanos(b.activeLimitMs() * 1_000_000));
                    var existing = request(task.requestId(), true);
                    if (!Objects.equals(number(existing, "learner_id"), task.learnerId())
                            || number(existing, "max_generation_calls") != b.generationCalls()
                            || number(existing, "max_retrieval_calls") != b.retrievalCalls()
                            || number(existing, "max_accounted_tokens") != b.maxAccountedTokens())
                        throw new GovernanceException("TASK_INVALID", "费用请求的归属或预算不匹配");
                });
    }

    /**
     * Host routing seals the execution profile once, before a Run exists; paid routing is never
     * reset.
     */
    public void selectProfile(PreparedStudyTask task) {
        if (!"BOUNDED_AGENT".equals(task.executionStrategy())) return;
        tasks.assertCurrent(task);
        durable.executeWithoutResult(
                s -> {
                    var r = request(task.requestId(), true);
                    if (!Objects.equals(number(r, "learner_id"), task.learnerId())
                            || r.get("run_id") != null)
                        throw new GovernanceException("TASK_INVALID", "不能变更已执行任务的预算");
                    if (number(r, "unknown_calls") > 0
                            || number(r, "accounted_tokens") > task.budget().maxAccountedTokens()
                            || number(r, "generation_calls") >= task.budget().generationCalls())
                        throw new GovernanceException("TASK_BUDGET_EXHAUSTED", "准备阶段已耗尽任务预算");
                    jdbc.update(
                            "UPDATE study_ai_requests SET"
                                + " profile=?,max_generation_calls=?,max_retrieval_calls=?,max_accounted_tokens=?"
                                + " WHERE id=?",
                            task.executionStrategy(),
                            task.budget().generationCalls(),
                            task.budget().retrievalCalls(),
                            task.budget().maxAccountedTokens(),
                            task.requestId());
                });
    }

    /**
     * Joins the Run creation transaction, including its FK; routing cost is carried into this run.
     */
    public void attach(PreparedStudyTask task, String runId) {
        var r = request(task.requestId(), true);
        if (!Objects.equals(number(r, "learner_id"), task.learnerId())
                || r.get("run_id") != null && !runId.equals(r.get("run_id")))
            throw new GovernanceException("TASK_INVALID", "费用请求归属不匹配");
        jdbc.update(
                "UPDATE study_ai_requests SET run_id=?,profile=? WHERE id=?",
                runId,
                task.executionStrategy(),
                task.requestId());
        jdbc.update(
                "UPDATE ai_call_attempts SET run_id=? WHERE request_id=? AND run_id IS NULL",
                runId,
                task.requestId());
        jdbc.update(
                "UPDATE video_agent_runs SET"
                    + " request_id=?,generation_calls=?,retrieval_calls=?,input_tokens=?,output_tokens=?,accounted_tokens=?,unknown_calls=?,token_usage_known=?,deadline_at=?,active_limit_ms=?,active_remaining_ms=?,wait_started_at=?"
                    + " WHERE id=?",
                task.requestId(),
                number(r, "generation_calls"),
                number(r, "retrieval_calls"),
                number(r, "input_tokens"),
                number(r, "output_tokens"),
                number(r, "accounted_tokens"),
                number(r, "unknown_calls"),
                number(r, "unknown_calls") == 0,
                date(r, "created_at").plusNanos(task.budget().wallLimitMs() * 1_000_000),
                task.budget().activeLimitMs(),
                Math.max(
                        0,
                        task.budget().activeLimitMs()
                                - Duration.between(date(r, "created_at"), runs.now()).toMillis()),
                runs.now(),
                runId);
        if (number(r, "generation_calls") + number(r, "retrieval_calls") > 0)
            runs.eventLocked(
                    runId,
                    0,
                    "PREPARATION_ACCOUNTED",
                    Map.of(
                            "generationCalls",
                            number(r, "generation_calls"),
                            "retrievalCalls",
                            number(r, "retrieval_calls"),
                            "accountedTokens",
                            number(r, "accounted_tokens")));
    }

    public record WireResponse(int status, String body, long retryAfterMillis) {
        public WireResponse(int status, String body) {
            this(status, body, 0);
        }
    }

    @FunctionalInterface
    public interface Transport {
        WireResponse send(Instant deadline) throws IOException;
    }

    public WireResponse exchange(
            String provider,
            String kind,
            JSONObject body,
            Runnable beforeAttempt,
            Instant suppliedDeadline,
            Transport transport) {
        // Preprocessing is recorded separately from students; it cannot inherit a student's request
        // by accident.
        if (StudyExecutionContext.current() == null) {
            String id = UUID.randomUUID().toString();
            durable.executeWithoutResult(
                    s ->
                            jdbc.update(
                                    "INSERT INTO"
                                        + " study_ai_requests(id,profile,max_generation_calls,max_retrieval_calls,max_accounted_tokens,created_at,deadline_at)"
                                        + " VALUES (?,'PREPROCESSING',3,3,2000000,?,?)",
                                    id,
                                    runs.now(),
                                    runs.now().plusMinutes(5)));
            try (var scope = StudyExecutionContext.preprocessing(id)) {
                return exchange(provider, kind, body, beforeAttempt, suppliedDeadline, transport);
            }
        }
        var ctx = StudyExecutionContext.require();
        String serialized = body.toJSONString();
        String fingerprint = sha256(canonicalJson(body));
        guard();
        var replay =
                jdbc.query(
                        "SELECT http_status,response_json FROM ai_call_attempts WHERE request_id=?"
                                + " AND purpose=? AND provider=? AND kind=? AND fingerprint=? AND"
                                + " status='SUCCEEDED' ORDER BY created_at DESC LIMIT 1",
                        (rs, n) -> new WireResponse(rs.getInt(1), rs.getString(2)),
                        ctx.requestId,
                        ctx.purpose,
                        provider,
                        kind,
                        fingerprint);
        if (!replay.isEmpty()) return replay.getFirst();
        Instant deadline = deadline(suppliedDeadline);
        return retries.execute(
                provider,
                () -> {
                    guard();
                    beforeAttempt.run();
                },
                () -> {
                    String attempt =
                            reserve(
                                    provider,
                                    kind,
                                    fingerprint,
                                    AiPromptBudget.estimateText(serialized),
                                    "GENERATION".equals(kind) ? body.getIntValue("max_tokens") : 0);
                    long start = System.nanoTime();
                    try {
                        sending(attempt);
                        WireResponse response = transport.send(deadline);
                        settle(attempt, response, (System.nanoTime() - start) / 1_000_000);
                        guard();
                        if (response.status() < 200 || response.status() >= 300)
                            throw AiProviderException.forStatus(
                                    "AI provider HTTP " + response.status(),
                                    null,
                                    response.status(),
                                    response.retryAfterMillis());
                        return response;
                    } catch (IOException error) {
                        unknown(
                                attempt,
                                "MODEL_OUTCOME_UNKNOWN",
                                (System.nanoTime() - start) / 1_000_000);
                        throw new GovernanceException(
                                "MODEL_OUTCOME_UNKNOWN", "供应商请求已发出但结果未知；已保留预留，不自动重投");
                    } catch (GovernanceException stopped) {
                        // If a response was saved, retain it and allow late usage; otherwise only
                        // an unsent reservation can be released.
                        notSent(attempt, stopped.code());
                        throw stopped;
                    } catch (AiProviderException known) {
                        throw known;
                    } catch (RuntimeException failure) {
                        // This includes persistence failing after receiving a provider response.
                        // Never replay an uncertain request.
                        unknown(
                                attempt,
                                "MODEL_OUTCOME_UNKNOWN",
                                (System.nanoTime() - start) / 1_000_000);
                        throw new GovernanceException(
                                "MODEL_OUTCOME_UNKNOWN", "供应商结果未可靠落库，已停止自动重投");
                    }
                },
                deadline);
    }

    /**
     * Public to allow deterministic crash-injection tests at the reservation/transport boundary.
     */
    public String reserve(
            String provider, String kind, String fingerprint, long input, long output) {
        var ctx = StudyExecutionContext.require();
        guard();
        return durable.execute(
                s -> {
                    if (ctx.runId != null) runs.lockLease(ctx.runId);
                    var r = request(ctx.requestId, true);
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ai_call_attempts WHERE request_id=? AND"
                                            + " status IN ('RESERVED','IN_FLIGHT','UNKNOWN')",
                                    Integer.class,
                                    ctx.requestId)
                            > 0)
                        throw new GovernanceException(
                                "MODEL_OUTCOME_UNKNOWN", "存在结果未知的供应商调用，不能自动重投");
                    boolean generation = "GENERATION".equals(kind);
                    String counter = generation ? "generation_calls" : "retrieval_calls";
                    long reservation = Math.addExact(input, output);
                    if (number(r, counter) >= number(r, "max_" + counter)
                            || number(r, "accounted_tokens") + reservation
                                    > number(r, "max_accounted_tokens"))
                        throw new GovernanceException(
                                "TASK_BUDGET_EXHAUSTED", "调用次数或累计 Token 治理预算已耗尽");
                    String id = UUID.randomUUID().toString();
                    jdbc.update(
                            "INSERT INTO"
                                + " ai_call_attempts(id,request_id,run_id,learner_id,execution_epoch,purpose,provider,kind,fingerprint,reserved_input,reserved_output,status,usage_status,created_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,'RESERVED','UNKNOWN',?)",
                            id,
                            ctx.requestId,
                            ctx.runId,
                            ctx.task == null ? null : ctx.task.learnerId(),
                            ctx.epoch,
                            ctx.purpose,
                            provider,
                            kind,
                            fingerprint,
                            input,
                            output,
                            runs.now());
                    jdbc.update(
                            "UPDATE study_ai_requests SET "
                                    + counter
                                    + "="
                                    + counter
                                    + "+1,accounted_tokens=accounted_tokens+?,unknown_calls=unknown_calls+1"
                                    + " WHERE id=?",
                            reservation,
                            ctx.requestId);
                    syncRun(ctx.requestId);
                    if (ctx.runId != null)
                        runs.eventLocked(
                                ctx.runId,
                                ctx.epoch,
                                "CALL_RESERVED",
                                Map.of(
                                        "provider",
                                        provider,
                                        "kind",
                                        kind,
                                        "reservedTokens",
                                        reservation));
                    return id;
                });
    }

    public void sending(String id) {
        guard();
        durable.executeWithoutResult(
                s -> {
                    var ctx = StudyExecutionContext.require();
                    if (ctx.runId != null) runs.lockLease(ctx.runId);
                    if (jdbc.update(
                                    "UPDATE ai_call_attempts SET status='IN_FLIGHT',sent_at=? WHERE"
                                            + " id=? AND status='RESERVED'",
                                    runs.now(),
                                    id)
                            != 1)
                        throw new GovernanceException("MODEL_OUTCOME_UNKNOWN", "调用预留状态异常");
                });
    }

    public void settle(String id, WireResponse response, long elapsed) {
        Long input = null, output = null;
        try {
            var json = JSON.parseObject(response.body());
            var usage = json == null ? null : json.getJSONObject("usage");
            if (usage != null) {
                input = usage.getLong("prompt_tokens");
                output = usage.getLong("completion_tokens");
                if (input == null && usage.getLong("total_tokens") != null) {
                    input = usage.getLong("total_tokens");
                    output = 0L;
                }
            }
        } catch (RuntimeException malformedUsage) {
            // Malformed usage does not prevent saving the successful raw response for recovery.
            input = null;
            output = null;
        }
        // A rejected request has no generated result; other failures retain unknown reservations
        // unless usage is provided.
        if (input == null
                && Set.of(400, 401, 403, 404, 413, 422, 429).contains(response.status())) {
            input = 0L;
            output = 0L;
        }
        Long in = input, out = output;
        durable.executeWithoutResult(
                s -> {
                    var a = attempt(id, false);
                    String runId = (String) a.get("run_id");
                    if (runId != null) runs.row(runId, true);
                    var r = request((String) a.get("request_id"), true);
                    a = attempt(id, true);
                    if (!List.of("RESERVED", "IN_FLIGHT", "UNKNOWN").contains(a.get("status")))
                        return;
                    boolean known = in != null && out != null && in >= 0 && out >= 0;
                    jdbc.update(
                            "UPDATE ai_call_attempts SET"
                                + " status=?,usage_status=?,input_tokens=?,output_tokens=?,http_status=?,response_json=?,error_code=?,elapsed_ms=?,finished_at=?"
                                + " WHERE id=?",
                            response.status() >= 200 && response.status() < 300
                                    ? "SUCCEEDED"
                                    : "FAILED",
                            known ? "KNOWN" : "UNKNOWN",
                            in,
                            out,
                            response.status(),
                            response.body(),
                            response.status() >= 200 && response.status() < 300
                                    ? null
                                    : "PROVIDER_HTTP_ERROR",
                            elapsed,
                            runs.now(),
                            id);
                    if (known)
                        jdbc.update(
                                "UPDATE study_ai_requests SET"
                                    + " accounted_tokens=accounted_tokens-?+?,input_tokens=input_tokens+?,output_tokens=output_tokens+?,unknown_calls=unknown_calls-1"
                                    + " WHERE id=?",
                                number(a, "reserved_input") + number(a, "reserved_output"),
                                in + out,
                                in,
                                out,
                                r.get("id"));
                    syncRun((String) r.get("id"));
                    if (runId != null)
                        runs.eventLocked(
                                runId,
                                number(a, "execution_epoch"),
                                "CALL_SETTLED",
                                Map.of(
                                        "kind",
                                        a.get("kind"),
                                        "elapsedMs",
                                        elapsed,
                                        "usageKnown",
                                        known,
                                        "inputTokens",
                                        known ? in : 0,
                                        "outputTokens",
                                        known ? out : 0));
                });
    }

    public void unknown(String id, String code, long elapsed) {
        durable.executeWithoutResult(
                s -> {
                    var a = attempt(id, false);
                    String runId = (String) a.get("run_id");
                    if (runId != null) runs.row(runId, true);
                    int changed =
                            jdbc.update(
                                    "UPDATE ai_call_attempts SET"
                                        + " status='UNKNOWN',error_code=?,elapsed_ms=?,finished_at=?"
                                        + " WHERE id=? AND status IN ('RESERVED','IN_FLIGHT')",
                                    code,
                                    elapsed,
                                    runs.now(),
                                    id);
                    if (changed == 1 && runId != null)
                        runs.eventLocked(
                                runId,
                                number(a, "execution_epoch"),
                                "CALL_OUTCOME_UNKNOWN",
                                Map.of("code", code, "elapsedMs", elapsed));
                });
    }

    private void notSent(String id, String code) {
        durable.executeWithoutResult(
                s -> {
                    var a = attempt(id, false);
                    String runId = (String) a.get("run_id");
                    if (runId != null) runs.row(runId, true);
                    var r = request((String) a.get("request_id"), true);
                    a = attempt(id, true);
                    // Only RESERVED is provably before dispatch. IN_FLIGHT is conservative even if
                    // local cancellation was very early.
                    if (!"RESERVED".equals(a.get("status"))) return;
                    jdbc.update(
                            "UPDATE ai_call_attempts SET"
                                + " status='NOT_SENT',usage_status='RELEASED',error_code=?,finished_at=?"
                                + " WHERE id=?",
                            code,
                            runs.now(),
                            id);
                    String counter =
                            "GENERATION".equals(a.get("kind"))
                                    ? "generation_calls"
                                    : "retrieval_calls";
                    jdbc.update(
                            "UPDATE study_ai_requests SET "
                                    + counter
                                    + "="
                                    + counter
                                    + "-1,accounted_tokens=accounted_tokens-?,unknown_calls=unknown_calls-1"
                                    + " WHERE id=?",
                            number(a, "reserved_input") + number(a, "reserved_output"),
                            r.get("id"));
                    syncRun((String) r.get("id"));
                });
    }

    public interface CallBinding extends AutoCloseable {
        @Override
        void close();
    }

    public CallBinding bind(Call call) {
        var ctx = StudyExecutionContext.require();
        String key = ctx.runId == null ? ctx.requestId : ctx.runId;
        calls.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(call);
        try {
            guard();
        } catch (RuntimeException stopped) {
            call.cancel();
            unbind(key, call);
            throw stopped;
        }
        return () -> unbind(key, call);
    }

    private void unbind(String key, Call call) {
        calls.computeIfPresent(
                key,
                (k, set) -> {
                    set.remove(call);
                    return set.isEmpty() ? null : set;
                });
    }

    public void cancelHttp(String runId) {
        var active = calls.get(runId);
        if (active != null) active.forEach(Call::cancel);
    }

    private Instant deadline(Instant supplied) {
        var ctx = StudyExecutionContext.require();
        Instant limit =
                ctx.runId == null
                        ? date(request(ctx.requestId, false), "deadline_at")
                                .atZone(ZoneId.systemDefault())
                                .toInstant()
                        : runs.deadline(ctx.runId);
        return supplied != null && supplied.isBefore(limit) ? supplied : limit;
    }

    private void guard() {
        var ctx = StudyExecutionContext.require();
        if (ctx.runId != null) runs.assertLease(ctx.runId);
        else if (ctx.task != null) tasks.assertCurrent(ctx.task);
    }

    private Map<String, Object> request(String id, boolean lock) {
        return jdbc.queryForMap(
                "SELECT * FROM study_ai_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private Map<String, Object> attempt(String id, boolean lock) {
        return jdbc.queryForMap(
                "SELECT * FROM ai_call_attempts WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private void syncRun(String requestId) {
        var r = request(requestId, false);
        if (r.get("run_id") == null) return;
        // Late settlement may update cost after cancellation, but never status, lease, results or
        // progress.
        jdbc.update(
                "UPDATE video_agent_runs SET"
                    + " generation_calls=?,retrieval_calls=?,input_tokens=?,output_tokens=?,accounted_tokens=?,unknown_calls=?,token_usage_known=?"
                    + " WHERE id=?",
                number(r, "generation_calls"),
                number(r, "retrieval_calls"),
                number(r, "input_tokens"),
                number(r, "output_tokens"),
                number(r, "accounted_tokens"),
                number(r, "unknown_calls"),
                number(r, "unknown_calls") == 0,
                r.get("run_id"));
    }

    public Map<String, Object> studentUsage(Long learner) {
        return jdbc.queryForMap(
                "SELECT COALESCE(SUM(generation_calls),0)"
                        + " generationCalls,COALESCE(SUM(retrieval_calls),0)"
                        + " retrievalCalls,COALESCE(SUM(input_tokens),0)"
                        + " inputTokens,COALESCE(SUM(output_tokens),0)"
                        + " outputTokens,COALESCE(SUM(accounted_tokens),0)"
                        + " accountedTokens,COALESCE(SUM(unknown_calls),0) unknownCalls FROM"
                        + " study_ai_requests WHERE learner_id=?",
                learner);
    }

    public static String canonicalJson(Object value) {
        return JSON.toJSONString(canonical(value));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(AiCallLedger::canonical).toList();
        return value;
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

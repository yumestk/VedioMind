package com.example.server.service;

import com.alibaba.fastjson2.*;
import com.example.server.dto.*;
import com.example.server.service.ai.*;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import com.example.server.service.ai.impl.DeepSeekChatClient;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Replay ordered native decisions and committed observations; successful writes also have durable
 * receipts.
 */
@Service
public class StudyAgentRuntime {
    private final VideoAgentRunService runs;
    private final DeepSeekChatClient model;
    private final StudyAgentToolDispatcher dispatcher;
    private final StudyArtifactService artifacts;

    public StudyAgentRuntime(
            VideoAgentRunService runs,
            DeepSeekChatClient model,
            StudyAgentToolDispatcher dispatcher,
            StudyArtifactService artifacts) {
        this.runs = runs;
        this.model = model;
        this.dispatcher = dispatcher;
        this.artifacts = artifacts;
    }

    public void execute(String runId) {
        var task = StudyExecutionContext.require().task;
        List<JSONObject> history =
                new ArrayList<>(
                        List.of(
                                JSONObject.of("role", "system", "content", StudyAgentSpec.PROMPT),
                                JSONObject.of(
                                        "role",
                                        "user",
                                        "content",
                                        "宿主冻结任务：" + runs.getRequired(runId).getPreparedTask())));
        Map<String, VideoAgentCitation> discovered = new LinkedHashMap<>(),
                read = new LinkedHashMap<>();
        Set<String> skills = new HashSet<>(), seen = new HashSet<>(), callIds = new HashSet<>();
        int calls = 0, corrections = 0, noEvidence = 0;
        try {
            // A crash after candidate commit must finalize without another paid model decision.
            if (artifacts.deliver(runId, null, null)) return;
            for (int round = 0; round < StudyAgentSpec.ROUNDS; round++) {
                runs.checkExecution(runId);
                runs.markStage(runId, "AGENT_DECISION", Math.min(85, 10 + round * 15));
                String cached = runs.completedStep(runId, "AGENT_DECISION", round).orElse(null);
                JSONObject decision;
                if (cached != null) decision = JSON.parseObject(cached);
                else {
                    try (var purpose = StudyExecutionContext.purpose("AGENT_DECISION:" + round)) {
                        try {
                            decision =
                                    model.tools(
                                            history,
                                            StudyAgentSpec.tools(),
                                            () -> runs.checkExecution(runId),
                                            runs.requestDeadline(runId));
                        } catch (AiProviderException invalid) {
                            if (!invalid.isRetryable())
                                throw new GovernanceException(
                                        "MODEL_RESPONSE_INVALID", "模型未返回合法的原生工具响应");
                            throw invalid;
                        }
                    }
                    runs.saveCompletedStep(runId, "AGENT_DECISION", round, null, decision);
                }
                if (runs.getRequired(runId).getAccountedTokens()
                        > task.budget().maxAccountedTokens())
                    throw new GovernanceException("TASK_BUDGET_EXHAUSTED", "实际用量已超过预留预算，停止后续工具执行");
                var wire = decision.getJSONArray("tool_calls");
                if (wire == null || wire.isEmpty())
                    throw new GovernanceException("NO_PROGRESS", "模型未保存约定笔记，正文不能替代工具交付");
                if (calls + wire.size() > StudyAgentSpec.TOOL_CALLS)
                    throw new GovernanceException("TASK_BUDGET_EXHAUSTED", "工具调用次数已达上限");
                List<StudyAgentToolDispatcher.Call> batch = new ArrayList<>();
                List<JSONObject> errors = new ArrayList<>();
                // Validate the whole batch before any side effect, including a save preceding a
                // forged tool.
                for (int i = 0; i < wire.size(); i++) {
                    var value = wire.getJSONObject(i);
                    if (!callIds.add(value.getString("id")))
                        throw new GovernanceException("TOOL_NOT_ALLOWED", "工具调用ID重复");
                    try {
                        batch.add(dispatcher.validate(value, task));
                        errors.add(null);
                    } catch (GovernanceException e) {
                        if (!"INVALID_TOOL_ARGUMENTS".equals(e.code())) throw e;
                        batch.add(null);
                        errors.add(JSONObject.of("error", e.code(), "message", e.getMessage()));
                    }
                }
                for (var call : batch)
                    if (call != null
                            && "read_evidence".equals(call.name())
                            && !discovered
                                    .keySet()
                                    .containsAll(call.args().getList("refs", String.class)))
                        throw new GovernanceException("TOOL_NOT_ALLOWED", "证据ID未由当前任务检索返回");
                history.add(decision);
                boolean invalidBatch = errors.stream().anyMatch(Objects::nonNull);
                for (int i = 0; i < wire.size(); i++) {
                    calls++;
                    String stage = "AGENT_TOOL_" + i;
                    String observation = runs.completedStep(runId, stage, round).orElse(null);
                    var call = batch.get(i);
                    JSONObject result;
                    if (observation != null) result = JSON.parseObject(observation);
                    else {
                        long toolStarted = System.nanoTime();
                        if (call != null
                                && "read_evidence".equals(call.name())
                                && read.keySet()
                                        .containsAll(call.args().getList("refs", String.class)))
                            throw new GovernanceException("NO_PROGRESS", "重复读取没有新增原文");
                        if (invalidBatch)
                            result =
                                    errors.get(i) == null
                                            ? JSONObject.of(
                                                    "error",
                                                    "INVALID_TOOL_ARGUMENTS",
                                                    "message",
                                                    "本批参数未全部通过，请修正后重新调用")
                                            : errors.get(i);
                        else {
                            String signature = call.name() + ":" + canonical(call.args());
                            if (seen.contains(signature))
                                throw new GovernanceException("NO_PROGRESS", "重复工具调用没有新增进展");
                            try (var purpose =
                                    StudyExecutionContext.purpose(
                                            "AGENT_TOOL:" + round + ":" + i)) {
                                result = dispatcher.execute(call, runId, skills, discovered, read);
                            } catch (GovernanceException e) {
                                if (!"INVALID_TOOL_ARGUMENTS".equals(e.code())) throw e;
                                result =
                                        JSONObject.of("error", e.code(), "message", e.getMessage());
                            }
                        }
                        runs.saveCompletedStep(
                                runId,
                                stage,
                                round,
                                JSONObject.of(
                                        "tool",
                                        wire.getJSONObject(i)
                                                .getJSONObject("function")
                                                .getString("name"),
                                        "elapsedMs",
                                        (System.nanoTime() - toolStarted) / 1_000_000),
                                result);
                    }
                    history.add(
                            JSONObject.of(
                                    "role",
                                    "tool",
                                    "tool_call_id",
                                    wire.getJSONObject(i).getString("id"),
                                    "content",
                                    AiCallLedger.canonicalJson(result)));
                    if (result.containsKey("error")) {
                        if (++corrections > StudyAgentSpec.CORRECTIONS)
                            throw new GovernanceException("INVALID_TOOL_ARGUMENTS", "工具参数修正次数已耗尽");
                        continue;
                    }
                    seen.add(call.name() + ":" + canonical(call.args()));
                    if ("read_skill".equals(call.name())) skills.add(result.getString("name"));
                    if ("search_course_evidence".equals(call.name())) {
                        int before = discovered.size();
                        for (var hit : result.getJSONArray("hits").toList(JSONObject.class)) {
                            // Only refs are needed for admission; original text is supplied by
                            // read_evidence.
                            discovered.putIfAbsent(hit.getString("ref"), null);
                        }
                        noEvidence = discovered.size() == before ? noEvidence + 1 : 0;
                        if (noEvidence >= 2)
                            throw new GovernanceException("NO_NEW_EVIDENCE", "连续检索未获得新增证据");
                    }
                    if ("read_evidence".equals(call.name()))
                        result.getList("evidence", VideoAgentCitation.class)
                                .forEach(e -> read.put(e.ref(), e));
                    if ("save_note".equals(call.name()) && artifacts.deliver(runId, null, null))
                        return;
                }
            }
            throw new GovernanceException("TASK_BUDGET_EXHAUSTED", "模型决策轮数已达上限");
        } catch (GovernanceException stop) {
            if (!Set.of("TASK_RESOURCE_CHANGED", "STALE_EXECUTION", "TOOL_NOT_ALLOWED")
                            .contains(stop.code())
                    && artifacts.deliver(runId, stop.code(), stop.getMessage())) return;
            throw stop;
        }
    }

    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> m) {
            var sorted = new TreeMap<String, Object>();
            m.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
            return JSON.toJSONString(sorted);
        }
        if (value instanceof Collection<?> c)
            return JSON.toJSONString(c.stream().map(StudyAgentRuntime::canonical).toList());
        return JSON.toJSONString(value);
    }
}

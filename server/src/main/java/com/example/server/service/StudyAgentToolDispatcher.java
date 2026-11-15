package com.example.server.service;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.example.server.dto.*;
import com.example.server.service.ai.*;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fixed registry. No model-supplied identity, arbitrary SQL, file access, HTTP or code execution.
 */
@Service
public class StudyAgentToolDispatcher {
    private final StudyTaskPreparationService tasks;
    private final StudyEvidenceService evidence;
    private final StudyArtifactService artifacts;
    private final VideoAgentRunService runs;

    public StudyAgentToolDispatcher(
            StudyTaskPreparationService tasks,
            StudyEvidenceService evidence,
            StudyArtifactService artifacts,
            VideoAgentRunService runs) {
        this.tasks = tasks;
        this.evidence = evidence;
        this.artifacts = artifacts;
        this.runs = runs;
    }

    public record Call(String id, String name, JSONObject args) {}

    public Call validate(JSONObject wire, PreparedStudyTask task) {
        var f = wire.getJSONObject("function");
        String name = f.getString("name");
        var definition =
                StudyAgentSpec.tools().stream()
                        .map(t -> t.getJSONObject("function"))
                        .filter(t -> name.equals(t.getString("name")))
                        .findFirst()
                        .orElseThrow(
                                () -> new GovernanceException("TOOL_NOT_ALLOWED", "工具不在当前任务允许列表中"));
        String raw = f.getString("arguments");
        JSONObject args;
        try {
            if (raw == null || raw.length() > 30000 || !JSON.isValidObject(raw))
                throw new IllegalArgumentException();
            args = JSON.parseObject(raw, JSONReader.Feature.DuplicateKeyValueAsArray);
            if (!JSONSchema.of(definition.getJSONObject("parameters")).isValid(args))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new GovernanceException("INVALID_TOOL_ARGUMENTS", "工具参数字段、类型或范围无效：" + name);
        }
        if (!"BOUNDED_AGENT".equals(task.executionStrategy()))
            throw new GovernanceException("TOOL_NOT_ALLOWED", "当前策略不允许工具执行");
        if ("save_note".equals(name)
                && task.deliverables().stream()
                        .noneMatch(
                                s ->
                                        s.slotId().equals(args.getString("slotId"))
                                                && "NOTE".equals(s.kind())))
            throw new GovernanceException("TOOL_NOT_ALLOWED", "输出槽不属于当前任务");
        return new Call(wire.getString("id"), name, args);
    }

    public JSONObject execute(
            Call call,
            String runId,
            Set<String> skills,
            Map<String, VideoAgentCitation> discovered,
            Map<String, VideoAgentCitation> read) {
        var task = StudyExecutionContext.require().task;
        runs.checkExecution(runId);
        tasks.assertCurrent(task);
        if (!call.name().equals("read_skill") && !skills.contains("course-notes"))
            throw new GovernanceException("INVALID_TOOL_ARGUMENTS", "执行前须先读取 course-notes Skill");
        JSONObject result;
        switch (call.name()) {
            case "read_skill" ->
                    result = StudyAgentSkills.bundled().read(call.args().getString("name"));
            case "get_course_structure" ->
                    result = JSONObject.of("scope", task.scope(), "materials", task.materials());
            case "search_course_evidence" -> {
                var hits =
                        evidence.search(task, call.args().getString("query")).stream()
                                .limit(12)
                                .map(VideoEvidenceContextService.Candidate::evidence)
                                .toList();
                // Search previews are leads, not read permissions for saving citations.
                result =
                        JSONObject.of(
                                "hits",
                                hits.stream()
                                        .map(
                                                e ->
                                                        JSONObject.of(
                                                                "ref",
                                                                e.ref(),
                                                                "materialId",
                                                                e.materialId(),
                                                                "startMs",
                                                                e.startMs(),
                                                                "preview",
                                                                e.text()
                                                                        .substring(
                                                                                0,
                                                                                Math.min(
                                                                                        160,
                                                                                        e.text()
                                                                                                .length()))))
                                        .toList());
            }
            case "read_evidence" -> {
                var refs = call.args().getList("refs", String.class);
                if (!discovered.keySet().containsAll(refs))
                    throw new GovernanceException("TOOL_NOT_ALLOWED", "证据ID未由当前任务检索返回");
                var window = evidence.read(task, refs);
                result = JSONObject.of("evidence", window.evidence(), "deferredWindowAnchors", window.deferredWindowAnchors(),
                        "window", JSONObject.of("secondsEachSide", 90, "transcriptsEachSide", 6,
                                "ocrEachSide", 1, "maxEstimatedInput", 14000),
                        "notice", "按请求顺序装入完整有界窗口；deferredWindowAnchors的窗口未完整读取，可另选锚点再读。邻域不代表完整章节；保留条件、指代和观点归属。只有实际返回的ID可引用。");
            }
            case "get_learning_state" -> result = (JSONObject) artifacts.learningState(task);
            case "save_note" ->
                    result =
                            artifacts.saveCandidate(
                                    runId,
                                    call.args().getString("slotId"),
                                    StudyArtifactService.noteFromPoints(call.args().getJSONObject("note")),
                                    call.args().getList("coverageMaterialIds", Long.class),
                                    read);
            default -> throw new GovernanceException("TOOL_NOT_ALLOWED", "未知工具");
        }
        runs.checkExecution(runId);
        tasks.assertCurrent(task);
        return result;
    }
}

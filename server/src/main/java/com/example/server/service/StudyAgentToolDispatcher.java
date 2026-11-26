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
        if (raw != null && raw.length() > 30000)
            throw new StudyToolFeedback("ARGUMENT_SIZE", "", "将参数缩减至 30000 字符以内。");
        JSONObject args;
        var schema = definition.getJSONObject("parameters");
        try {
            if (raw == null || !JSON.isValidObject(raw))
                throw raw != null && JSON.isValid(raw)
                        ? new StudyToolFeedback("SCHEMA", "", "工具参数须为 JSON 对象。")
                        : new StudyToolFeedback("JSON_SYNTAX", "", "重新提交可解析的 JSON 对象。");
            args = JSON.parseObject(raw, JSONReader.Feature.DuplicateKeyValueAsArray);
        } catch (StudyToolFeedback invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new StudyToolFeedback("JSON_SYNTAX", "", "重新提交可解析的 JSON 对象。");
        }
        try {
            if (!JSONSchema.of(schema).isValid(args)) throw StudyToolFeedback.schema(schema, args, "");
        } catch (StudyToolFeedback invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new StudyToolFeedback("SCHEMA", "", "按工具 Schema 修正参数。");
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

    StudyEvidenceService.ReadWindow readHostSupplement(String runId, String ref) {
        runs.checkExecution(runId);
        var result = evidence.readHostSupplement(StudyExecutionContext.require().task, ref);
        runs.checkExecution(runId);
        return result;
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
        if (!skills.contains(StudyAgentSkills.REQUIRED_NOTE))
            throw new GovernanceException("TASK_RESOURCE_CHANGED", "宿主尚未验证必读的 course-notes Skill");
        JSONObject result;
        switch (call.name()) {
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
            case "save_note" -> {
                return artifacts.saveAndDeliver(
                        runId,
                        call.args().getString("slotId"),
                        StudyArtifactService.noteFromPoints(call.args().getJSONObject("note")),
                        call.args().getList("coverageMaterialIds", Long.class),
                        read);
            }
            default -> throw new GovernanceException("TOOL_NOT_ALLOWED", "未知工具");
        }
        runs.checkExecution(runId);
        tasks.assertCurrent(task);
        return result;
    }
}

package com.example.server.service;

import com.alibaba.fastjson2.*;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.example.server.dto.*;
import com.example.server.service.ai.AiCallLedger;
import com.example.server.service.ai.StudyExecutionContext;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/**
 * Candidate receipts are private to the runtime; only atomic delivery makes a note student-visible.
 */
@Service
public class StudyArtifactService {
    private final JdbcTemplate jdbc;
    private final StudyRunControl control;
    private final StudyTaskPreparationService tasks;

    public StudyArtifactService(
            JdbcTemplate jdbc, StudyRunControl control, StudyTaskPreparationService tasks) {
        this.jdbc = jdbc;
        this.control = control;
        this.tasks = tasks;
    }

    public record Artifact(
            String id,
            String runId,
            String slotId,
            long version,
            boolean edited,
            VideoAgentResult content,
            List<Source> citations,
            List<Long> coverageMaterialIds) {}

    public record Source(VideoAgentCitation evidence, boolean available) {}

    public record Edit(long baseVersion, JSONObject content) {}

    public static VideoAgentResult validate(
            JSONObject payload, Map<String, VideoAgentCitation> evidence) {
        if (!JSONSchema.of(StudyAgentSpec.noteSchema()).isValid(payload))
            throw new GovernanceException("INVALID_TOOL_ARGUMENTS", "笔记字段、类型、长度或知识点数量不符合约定");
        var note = payload.toJavaObject(VideoAgentResult.class);
        var refs = refs(note);
        if (refs.stream().anyMatch(ref -> !evidence.containsKey(ref))
                || note.title().isBlank()
                || note.coreConclusion().isBlank()
                || note.knowledgePoints().stream()
                        .anyMatch(p -> p.title().isBlank() || p.explanation().isBlank()))
            throw new GovernanceException("INVALID_TOOL_ARGUMENTS", "只能引用本任务已读取的原文，内容不能为空");
        return note;
    }

    private static Set<String> refs(VideoAgentResult note) {
        Set<String> refs = new LinkedHashSet<>(note.coreEvidenceRefs());
        note.knowledgePoints().forEach(p -> refs.addAll(p.evidenceRefs()));
        return refs;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public JSONObject saveCandidate(
            String runId,
            String slotId,
            JSONObject payload,
            List<Long> coverage,
            Map<String, VideoAgentCitation> readEvidence) {
        var task = StudyExecutionContext.require().task;
        tasks.lockCurrent(task);
        control.lockLease(runId);
        if (!"BOUNDED_AGENT".equals(task.executionStrategy())
                || task.deliverables().stream()
                        .noneMatch(s -> s.slotId().equals(slotId) && "NOTE".equals(s.kind())))
            throw new GovernanceException("TOOL_NOT_ALLOWED", "输出槽不属于当前任务");
        var note = validate(payload, readEvidence);
        var used = refs(note).stream().map(readEvidence::get).toList();
        var actual =
                used.stream()
                        .map(VideoAgentCitation::materialId)
                        .collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(new HashSet<>(coverage))
                || coverage.size() != actual.size()
                || !task.scope().materialIds().containsAll(actual))
            throw new GovernanceException("INVALID_TOOL_ARGUMENTS", "覆盖范围须与引用的材料一致且不能越界");
        String operation = runId + ":" + slotId + ":1";
        // Deserialize into the closed DTO before hashing: JSON object ordering is not a semantic
        // change.
        var sorted = coverage.stream().sorted().toList();
        String fingerprint =
                AiCallLedger.sha256(JSON.toJSONString(note) + JSON.toJSONString(sorted) + ":0");
        var old =
                jdbc.queryForList(
                        "SELECT * FROM study_artifacts WHERE operation_id=? FOR UPDATE", operation);
        if (!old.isEmpty()) {
            var row = old.getFirst();
            if (!fingerprint.equals(row.get("payload_fingerprint"))
                    || ((Number) row.get("version")).longValue() != 1
                    || Boolean.TRUE.equals(row.get("edited")))
                throw new GovernanceException("ARTIFACT_WRITE_CONFLICT", "已有成果版本或保存内容不同，不能覆盖");
            return receipt(row.get("id").toString(), slotId, operation);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO"
                    + " study_artifacts(id,run_id,learner_id,slot_id,kind,operation_id,payload_fingerprint,base_version,content_json,citations_json,coverage_json,created_at,updated_at)"
                    + " VALUES (?,?,?,?,'NOTE',?,?,0,?,?,?,?,?)",
                id,
                runId,
                task.learnerId(),
                slotId,
                operation,
                fingerprint,
                JSON.toJSONString(note),
                JSON.toJSONString(used),
                JSON.toJSONString(sorted),
                control.now(),
                control.now());
        control.eventLocked(
                runId,
                StudyExecutionContext.epoch(runId),
                "ARTIFACT_CANDIDATE_SAVED",
                Map.of("kind", "NOTE", "slotId", slotId));
        return receipt(id, slotId, operation);
    }

    private JSONObject receipt(String id, String slot, String operation) {
        return JSONObject.of(
                "artifactId",
                id,
                "slotId",
                slot,
                "operationId",
                operation,
                "version",
                1,
                "status",
                "CANDIDATE");
    }

    /**
     * Source fences, formal visibility, Run result, terminal status and completion event commit
     * together.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean deliver(String runId, String stopCode, String reason) {
        var task = StudyExecutionContext.require().task;
        tasks.lockCurrent(task);
        var run = control.row(runId, true);
        if (!"RUNNING".equals(run.get("status"))
                || StudyRunControl.number(run, "execution_epoch")
                        != StudyExecutionContext.epoch(runId)) return false;
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM study_artifacts WHERE run_id=? ORDER BY slot_id FOR UPDATE",
                        runId);
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        var note = JSON.parseObject(row.get("content_json").toString(), VideoAgentResult.class);
        var coverage = JSON.parseArray(row.get("coverage_json").toString(), Long.class);
        boolean complete =
                task.deliverables().size() == rows.size()
                        && note.gaps().isEmpty()
                        && new HashSet<>(coverage)
                                .equals(new HashSet<>(task.scope().materialIds()));
        jdbc.update(
                "UPDATE study_artifacts SET status='DELIVERED',updated_at=? WHERE run_id=?",
                control.now(),
                runId);
        jdbc.update(
                "UPDATE video_agent_runs SET result_json=?,citations_json=? WHERE id=?",
                row.get("content_json").toString(),
                row.get("citations_json").toString(),
                runId);
        control.eventLocked(
                runId,
                StudyExecutionContext.epoch(runId),
                "ARTIFACT_DELIVERED",
                Map.of("count", rows.size(), "complete", complete));
        if (stopCode == null
                && (!control.now().isBefore(StudyRunControl.date(run, "deadline_at"))
                        || StudyRunControl.number(run, "active_remaining_ms")
                                <= java.time.Duration.between(
                                                StudyRunControl.date(run, "active_started_at"),
                                                control.now())
                                        .toMillis())) {
            stopCode = "TASK_DEADLINE_EXCEEDED";
            reason = "截止时间已到，交付已保存的部分笔记";
        }
        if (stopCode == null
                && StudyRunControl.number(run, "accounted_tokens")
                        > task.budget().maxAccountedTokens()) {
            stopCode = "TASK_BUDGET_EXHAUSTED";
            reason = "实际用量超过预算，交付已保存的部分笔记";
        }
        if (complete && stopCode == null) control.completeLocked(run);
        else
            control.stop(
                    runId,
                    stopCode == null ? "TASK_INCOMPLETE" : stopCode,
                    reason == null ? "已交付部分笔记；所选材料覆盖或证据缺口尚未完成" : reason,
                    "PARTIAL",
                    true);
        return true;
    }

    public List<Artifact> list(String runId, Long learner) {
        var run = control.row(runId, false);
        if (StudyRunControl.number(run, "learner_id") != learner)
            throw new NoSuchElementException("Study run not found");
        return jdbc
                .queryForList(
                        "SELECT * FROM study_artifacts WHERE run_id=? AND learner_id=? AND"
                                + " status='DELIVERED' ORDER BY slot_id",
                        runId,
                        learner)
                .stream()
                .map(this::view)
                .toList();
    }

    private Artifact view(Map<String, Object> row) {
        var task = control.task(control.row(row.get("run_id").toString(), false));
        var citations =
                JSON
                        .parseArray(row.get("citations_json").toString(), VideoAgentCitation.class)
                        .stream()
                        .map(
                                e -> {
                                    boolean available =
                                            tasks.sourceAvailable(
                                                    e.materialId(),
                                                    task.courseId(),
                                                    task.learnerId(),
                                                    e.contextVersion());
                                    var safe =
                                            available
                                                    ? e
                                                    : new VideoAgentCitation(
                                                            e.ref(),
                                                            e.type(),
                                                            e.sourceId(),
                                                            e.startMs(),
                                                            e.endMs(),
                                                            "",
                                                            e.materialId(),
                                                            e.contextVersion());
                                    return new Source(safe, available);
                                })
                        .toList();
        return new Artifact(
                row.get("id").toString(),
                row.get("run_id").toString(),
                row.get("slot_id").toString(),
                ((Number) row.get("version")).longValue(),
                Boolean.TRUE.equals(row.get("edited")),
                JSON.parseObject(row.get("content_json").toString(), VideoAgentResult.class),
                citations,
                JSON.parseArray(row.get("coverage_json").toString(), Long.class));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Artifact edit(String id, Long learner, Edit edit) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM study_artifacts WHERE id=? AND learner_id=? AND"
                                + " status='DELIVERED' FOR UPDATE",
                        id,
                        learner);
        if (rows.isEmpty()) throw new NoSuchElementException("Note not found");
        var row = rows.getFirst();
        if (edit.baseVersion() != ((Number) row.get("version")).longValue())
            throw new GovernanceException("ARTIFACT_WRITE_CONFLICT", "笔记已被修改，请刷新后再保存");
        var citations =
                JSON.parseArray(row.get("citations_json").toString(), VideoAgentCitation.class);
        var map = new LinkedHashMap<String, VideoAgentCitation>();
        citations.forEach(e -> map.put(e.ref(), e));
        var note = validate(edit.content(), map);
        var used = refs(note).stream().map(map::get).toList();
        var coverage =
                used.stream().map(VideoAgentCitation::materialId).distinct().sorted().toList();
        String contentJson = JSON.toJSONString(note),
                citationsJson = JSON.toJSONString(used),
                coverageJson = JSON.toJSONString(coverage);
        jdbc.update(
                "UPDATE study_artifacts SET"
                    + " content_json=?,citations_json=?,coverage_json=?,version=version+1,edited=TRUE,updated_at=?"
                    + " WHERE id=?",
                contentJson,
                citationsJson,
                coverageJson,
                control.now(),
                id);
        row.put("content_json", contentJson);
        row.put("citations_json", citationsJson);
        row.put("coverage_json", coverageJson);
        row.put("version", edit.baseVersion() + 1);
        row.put("edited", true);
        return view(row);
    }

    public Object learningState(PreparedStudyTask task) {
        // No quiz submissions exist yet; never manufacture mastery/weakness statistics from chats.
        return JSONObject.of(
                "hasSubmissionRecords",
                false,
                "mastery",
                "UNKNOWN",
                "notes",
                jdbc.queryForList(
                        "SELECT"
                            + " a.id,a.version,a.edited,JSON_UNQUOTE(JSON_EXTRACT(a.content_json,'$.title'))"
                            + " title FROM study_artifacts a JOIN video_agent_runs r ON"
                            + " r.id=a.run_id WHERE a.learner_id=? AND a.status='DELIVERED' AND"
                            + " r.course_id <=> ? AND JSON_CONTAINS(?,a.coverage_json)=1 ORDER BY"
                            + " a.updated_at DESC LIMIT 10",
                        task.learnerId(),
                        task.courseId(),
                        JSON.toJSONString(task.scope().materialIds())));
    }
}

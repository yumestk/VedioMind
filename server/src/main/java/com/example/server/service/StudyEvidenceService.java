package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import com.example.server.service.context.VideoContext;
import com.example.server.service.context.VideoContextService;
import org.springframework.stereotype.Service;
import java.util.*;

/** Bounded fan-out. Authorize/freeze first, then query only those media IDs AND versions. */
@Service
public class StudyEvidenceService {
    private final StudyTaskPreparationService tasks;
    private final VideoContextService contexts;
    private final VideoKnowledgeIndexService indexes;
    private final VideoKnowledgeRetriever retriever;
    private final VideoEvidenceContextService neighborhoods;

    public StudyEvidenceService(StudyTaskPreparationService tasks, VideoContextService contexts,
            VideoKnowledgeIndexService indexes, VideoKnowledgeRetriever retriever, VideoEvidenceContextService neighborhoods) {
        this.tasks = tasks; this.contexts = contexts; this.indexes = indexes;
        this.retriever = retriever; this.neighborhoods = neighborhoods;
    }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<VideoEvidenceContextService.Candidate> full(PreparedStudyTask task) {
        tasks.assertCurrent(task);
        tasks.lockCurrent(task);
        List<VideoEvidenceContextService.Candidate> evidence = new ArrayList<>();
        for (var m : task.materials()) {
            tasks.assertCurrent(task);
            var context = contexts.build(m.materialId());
            requireVersion(m, context);
            if (context.transcriptEvidence().isEmpty() && context.visualEvidence().stream().noneMatch(v -> v.ocrText() != null && !v.ocrText().isBlank()))
                throw new VideoConversationService.ConversationNotReadyException("材料“" + m.title() + "”没有可用原文，请联系课程作者完成准备");
            for (var t : context.transcriptEvidence()) evidence.add(scoped(m,
                    new VideoEvidenceContextService.Candidate(new VideoAgentCitation("TRANSCRIPT:" + t.id(),
                            "TRANSCRIPT", t.id(), t.startMs(), t.endMs(), t.text()), true, 0)));
            for (var v : context.visualEvidence()) if (v.ocrText() != null && !v.ocrText().isBlank())
                evidence.add(scoped(m, new VideoEvidenceContextService.Candidate(new VideoAgentCitation("OCR:" + v.id(),
                        "OCR", v.id(), v.timestampMs(), v.timestampMs(), v.ocrText()), true, 0)));
        }
        tasks.assertCurrent(task);
        return List.copyOf(evidence);
    }

    public List<VideoEvidenceContextService.Candidate> search(PreparedStudyTask task, String query) {
        tasks.assertCurrent(task);
        var snapshots = task.materials().stream().map(m -> {
            tasks.assertCurrent(task);
            var index = indexes.requireReady(m.materialId());
            requireVersion(m, index.context());
            return index;
        }).toList();
        var hits = retriever.searchMaterials(snapshots, query, () -> tasks.assertCurrent(task));
        List<List<VideoEvidenceContextService.Candidate>> byMaterial = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            var ranked = neighborhoods.searchLeads(snapshots.get(i).context(), hits.get(i), query);
            var material = task.materials().get(i);
            byMaterial.add(ranked.stream().map(c -> scoped(material, c)).toList());
        }
        Map<String, VideoEvidenceContextService.Candidate> unique = new LinkedHashMap<>();
        int longest = byMaterial.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) for (var values : byMaterial)
            if (i < values.size()) unique.putIfAbsent(values.get(i).evidence().ref(), values.get(i));
        tasks.assertCurrent(task);
        return List.copyOf(unique.values());
    }

    public record ReadWindow(List<VideoAgentCitation> evidence, List<String> deferredWindowAnchors) {}

    /** Host-derived originals only; each returned neighbor retains its own scoped citation ID. */
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ReadWindow read(PreparedStudyTask task, List<String> refs) {
        var originals = full(task).stream().map(VideoEvidenceContextService.Candidate::evidence).toList();
        var byRef = new HashMap<String, VideoAgentCitation>();
        originals.forEach(e -> byRef.put(e.ref(), e));
        var anchors = refs.stream().map(byRef::get).filter(Objects::nonNull).toList();
        if (anchors.size() != refs.size())
            throw new GovernanceException("TASK_RESOURCE_CHANGED", "原文证据已变化");
        var selected = new LinkedHashMap<String, VideoAgentCitation>();
        List<String> deferred = new ArrayList<>();
        for (var anchor : anchors) {
            var window = new LinkedHashMap<String, VideoAgentCitation>();
            window.put(anchor.ref(), anchor);
            var sameSource = originals.stream().filter(e -> Objects.equals(e.materialId(), anchor.materialId())
                    && Objects.equals(e.contextVersion(), anchor.contextVersion())
                    && e.endMs() >= Math.max(0, anchor.startMs() - 90_000)
                    && e.startMs() <= anchor.endMs() + 90_000).toList();
            for (String type : List.of("TRANSCRIPT", "OCR")) for (boolean before : List.of(true, false)) {
                sameSource.stream().filter(e -> type.equals(e.type()) && !e.ref().equals(anchor.ref())
                                && (before ? e.startMs() <= anchor.startMs() : e.startMs() > anchor.startMs()))
                        .sorted(Comparator.comparingLong(e -> Math.abs(e.startMs() - anchor.startMs())))
                        .limit("TRANSCRIPT".equals(type) ? 6 : 1)
                        .forEach(e -> window.putIfAbsent(e.ref(), e));
            }
            var trial = new LinkedHashMap<>(selected);
            trial.putAll(window);
            if (AiPromptBudget.estimateText(JSON.toJSONString(trial.values())) <= 14_000) selected = trial;
            else deferred.add(anchor.ref());
        }
        var values = selected.values().stream().sorted(Comparator.comparing(VideoAgentCitation::materialId)
                .thenComparingLong(VideoAgentCitation::startMs).thenComparing(VideoAgentCitation::ref)).toList();
        // Keep each accepted window intact. Deferred anchors grant no additional read permissions.
        if (values.isEmpty())
            throw new GovernanceException("EVIDENCE_WINDOW_TOO_LARGE", "单锚点完整窗口超过14k输入预算；请换更短的原文锚点。未读取不代表课程未讲过，禁止重复相同读取");
        return new ReadWindow(values, List.copyOf(deferred));
    }

    @org.springframework.transaction.annotation.Transactional(
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
            timeout = StudyHostSupplement.READ_TIMEOUT_SECONDS)
    public ReadWindow readHostSupplement(PreparedStudyTask task, String ref) {
        return read(task, List.of(ref));
    }

    private void requireVersion(PreparedStudyTask.Material material, VideoContext context) {
        if (!material.materialId().equals(context.mediaId()) || !material.version().equals(context.version())
                || !Objects.equals(material.visualState(), context.visual().state()))
            throw new StudyTaskPreparationService.SourceChangedException("检索材料版本已变更，请重新提交任务");
    }
    private VideoEvidenceContextService.Candidate scoped(PreparedStudyTask.Material m, VideoEvidenceContextService.Candidate c) {
        var e = c.evidence();
        String ref = m.materialId() + ":" + e.type() + ":" + e.sourceId() + ":" + m.version();
        return new VideoEvidenceContextService.Candidate(new VideoAgentCitation(ref, e.type(), e.sourceId(),
                e.startMs(), e.endMs(), e.text(), m.materialId(), m.version()), c.core(), c.distanceMs());
    }
}

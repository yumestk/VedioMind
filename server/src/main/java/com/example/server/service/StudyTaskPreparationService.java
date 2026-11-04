package com.example.server.service;

import com.example.server.dto.PreparedStudyTask;
import com.example.server.dto.PreparedStudyTask.*;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.context.VideoContextService;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class StudyTaskPreparationService {
    public static final int MAX_MATERIALS = 12;
    private static final Pattern CHAPTER = Pattern.compile("第[零〇一二两三四五六七八九十百0-9]+[章节]");
    private final StudyAccessService access;
    private final CourseService courses;
    private final VideoContextService contexts;
    private final AiPromptBudget budget;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public StudyTaskPreparationService(
            StudyAccessService access,
            CourseService courses,
            VideoContextService contexts,
            AiPromptBudget budget,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.access = access;
        this.courses = courses;
        this.contexts = contexts;
        this.budget = budget;
    }

    /**
     * Resolve before any paid routing, quota, conversation creation or MQ submission. No model
     * guesses permissions.
     */
    public PreparedStudyTask prepare(
            Long mediaId,
            Long learnerId,
            Long conversationId,
            Long courseId,
            Scope requested,
            String goal) {
        access.requireRead(mediaId, courseId, learnerId);
        if (goal == null || goal.isBlank() || goal.trim().length() > 500)
            throw new IllegalArgumentException("任务目标须为 1–500 字");
        var detail = courseId == null ? null : courses.detail(courseId, learnerId);
        Scope scope = requested;
        if (scope == null) {
            var chapters =
                    CHAPTER.matcher(goal)
                            .results()
                            .map(java.util.regex.MatchResult::group)
                            .distinct()
                            .toList();
            if (chapters.size() > 1) throw new NeedsInputException("请求涉及多个章节，请明确选择章节、整门课程或材料范围");
            if (!chapters.isEmpty()) {
                String label = chapters.getFirst();
                if (detail == null) throw new NeedsInputException("请从课程进入并选择要学习的章节");
                var matches =
                        detail.sections().stream()
                                .filter(
                                        s ->
                                                s.title().startsWith(label)
                                                        && (s.title().length() == label.length()
                                                                || !Character.isDigit(
                                                                        s.title()
                                                                                .charAt(
                                                                                        label
                                                                                                .length()))))
                                .toList();
                if (matches.size() != 1)
                    throw new NeedsInputException("无法唯一确定“" + label + "”，请选择章节范围");
                scope = new Scope("SECTION", matches.getFirst().id(), List.of());
            } else if (goal.contains("整门课") || goal.contains("整个课程") || goal.contains("全课程")) {
                if (detail == null) throw new NeedsInputException("请先选择课程");
                scope = new Scope("COURSE", null, List.of());
            } else scope = new Scope("VIDEO", null, List.of(mediaId));
        }
        if (scope.type() == null
                || !Set.of("VIDEO", "SECTION", "COURSE", "MATERIALS").contains(scope.type()))
            throw new IllegalArgumentException("未知任务范围");
        if (!"SECTION".equals(scope.type()) && scope.sectionId() != null)
            throw new IllegalArgumentException("章节参数与任务范围不一致");
        if (!List.of("VIDEO", "MATERIALS").contains(scope.type()) && !scope.materialIds().isEmpty())
            throw new IllegalArgumentException("此任务范围不能附加材料列表");
        if ("VIDEO".equals(scope.type())
                && !scope.materialIds().isEmpty()
                && !scope.materialIds().equals(List.of(mediaId)))
            throw new IllegalArgumentException("当前视频范围不能指向其他材料");
        List<Material> selected = new ArrayList<>();
        if (detail == null) {
            if (!"VIDEO".equals(scope.type())) throw new NeedsInputException("请先选择课程，再选择章节或课程材料");
            selected.add(
                    new Material(
                            mediaId,
                            null,
                            null,
                            "当前视频",
                            contexts.currentVersion(mediaId),
                            contexts.currentVisualState(mediaId)));
        } else {
            if ("SECTION".equals(scope.type()) && scope.sectionId() == null)
                throw new NeedsInputException("请选择章节");
            if ("SECTION".equals(scope.type()) && !hasSection(detail, scope.sectionId()))
                throw new NoSuchElementException("Section not found in this course");
            Set<Long> wanted =
                    new LinkedHashSet<>(
                            "VIDEO".equals(scope.type()) ? List.of(mediaId) : scope.materialIds());
            if ("MATERIALS".equals(scope.type())
                    && (wanted.isEmpty() || wanted.size() != scope.materialIds().size()))
                throw new IllegalArgumentException("请指定不重复的课程材料");
            for (var section : detail.sections())
                for (var material : section.materials()) {
                    boolean include =
                            switch (scope.type()) {
                                case "COURSE" -> true;
                                case "SECTION" -> section.id().equals(scope.sectionId());
                                default -> wanted.contains(material.mediaId());
                            };
                    if (include)
                        selected.add(
                                new Material(
                                        material.mediaId(),
                                        material.id(),
                                        section.id(),
                                        material.title(),
                                        "",
                                        null));
                }
            if (List.of("VIDEO", "MATERIALS").contains(scope.type())
                    && selected.size() != wanted.size())
                throw new NoSuchElementException("Material not found in this course");
            if (selected.size() > MAX_MATERIALS)
                throw new NeedsInputException("范围超过 12 个视频，请选择一个章节或更少材料");
            // Resolve every permission before loading any source/version.
            selected.forEach(m -> access.requireRead(m.materialId(), courseId, learnerId));
            selected =
                    selected.stream()
                            .map(
                                    m ->
                                            new Material(
                                                    m.materialId(),
                                                    m.courseMaterialId(),
                                                    m.sectionId(),
                                                    m.title(),
                                                    contexts.currentVersion(m.materialId()),
                                                    contexts.currentVisualState(m.materialId())))
                            .toList();
        }
        if (selected.isEmpty()) throw new NeedsInputException("所选范围没有可用视频，请选择其他章节");
        var frozenScope =
                new Scope(
                        scope.type(),
                        scope.sectionId(),
                        selected.stream().map(Material::materialId).toList());
        var task =
                PreparedStudyTask.create(
                        UUID.randomUUID().toString(),
                        learnerId,
                        conversationId,
                        courseId,
                        mediaId,
                        detail == null ? null : detail.course().structureVersion(),
                        frozenScope,
                        selected,
                        goal,
                        goal.trim(),
                        List.of("ANSWER"),
                        "COURSE_MATERIAL_ONLY",
                        Budget.standard(
                                budget.inputLimit(),
                                budget.outputTokens(),
                                VideoAgentRunService.MAX_GENERATION_CALLS,
                                VideoAgentRunService.MAX_RETRIEVAL_CALLS,
                                MAX_MATERIALS));
        assertCurrent(task);
        return task;
    }

    private boolean hasSection(CourseService.Detail detail, Long id) {
        return detail.sections().stream().anyMatch(s -> s.id().equals(id));
    }

    public PreparedStudyTask forMode(PreparedStudyTask task, String mode) {
        List<String> slots =
                "DEEP".equals(mode)
                        ? (VideoAgentWorkflowService.requestsQuiz(task.goal())
                                ? List.of("NOTE", "QUIZ")
                                : List.of("NOTE"))
                        : List.of("ANSWER");
        if ("DEEP".equals(mode) && !"VIDEO".equals(task.scope().type())) {
            if (slots.contains("QUIZ"))
                throw new NeedsInputException("跨视频笔记已支持；跨视频测验将在下一阶段接入，请先只生成笔记");
            return task.boundedNotes();
        }
        return task.forDeliverables(slots);
    }

    public void assertCurrent(PreparedStudyTask task) {
        boolean bounded = "BOUNDED_AGENT".equals(task.executionStrategy());
        var expected =
                bounded
                        ? Budget.boundedStudy(
                                budget.inputLimit(), budget.outputTokens(), MAX_MATERIALS)
                        : Budget.standard(
                                budget.inputLimit(), budget.outputTokens(), 3, 6, MAX_MATERIALS);
        if (!"study-task-v1".equals(task.contractVersion())
                || !expected.equals(task.budget())
                || bounded
                        && (!StudyAgentSpec.snapshot().equals(task.resourceSnapshot())
                                || !task.deliverables().stream()
                                        .map(OutputSlot::kind)
                                        .toList()
                                        .equals(List.of("NOTE"))))
            throw new SourceChangedException("任务契约、工具、Skill或预算配置已变更，请重新提交任务");
        access.requireRead(task.anchorMediaId(), task.courseId(), task.learnerId());
        if (task.courseId() != null
                && courses.detail(task.courseId(), task.learnerId()).course().structureVersion()
                        != task.courseVersion())
            throw new SourceChangedException("课程结构已变更，请重新提交任务");
        for (var m : task.materials()) {
            access.requireRead(m.materialId(), task.courseId(), task.learnerId());
            if (!Objects.equals(m.version(), contexts.currentVersion(m.materialId()))
                    || !Objects.equals(
                            m.visualState(), contexts.currentVisualState(m.materialId())))
                throw new SourceChangedException("材料版本已变更，请重新提交任务");
        }
    }

    public boolean sourcesAvailable(com.example.server.entity.VideoAgentRun run) {
        if (run.getPreparedTask() == null)
            return sourceAvailable(
                    run.getMediaId(),
                    run.getCourseId(),
                    run.getLearnerId(),
                    run.getContextVersion());
        var task =
                com.alibaba.fastjson2.JSON.parseObject(
                        run.getPreparedTask(), PreparedStudyTask.class);
        return task.materials().stream()
                .allMatch(
                        m ->
                                sourceAvailable(
                                        m.materialId(),
                                        task.courseId(),
                                        task.learnerId(),
                                        m.version()));
    }

    public boolean sourceAvailable(Long materialId, Long courseId, Long learnerId, String version) {
        return access.canRead(materialId, courseId, learnerId)
                && Objects.equals(version, contexts.currentVersion(materialId));
    }

    /** Called inside the result transaction, fences course/source mutation through commit. */
    public void lockCurrent(PreparedStudyTask task) {
        task.materials().stream()
                .sorted(Comparator.comparing(Material::materialId))
                .forEach(
                        m ->
                                access.lockReadable(
                                        m.materialId(), task.courseId(), task.learnerId()));
        for (var m : task.materials())
            jdbc.queryForList(
                    "SELECT media_id FROM media_visual_evidence WHERE media_id=? FOR SHARE",
                    Long.class,
                    m.materialId());
        assertCurrent(task);
    }

    public static class NeedsInputException extends RuntimeException {
        public NeedsInputException(String message) {
            super(message);
        }
    }

    public static class SourceChangedException extends IllegalArgumentException {
        public SourceChangedException(String message) {
            super(message);
        }
    }
}

package com.example.server.dto;

import java.util.List;
import java.util.Map;

/** Host-owned immutable task. materialId is the media source; courseMaterialId is its course association. */
public record PreparedStudyTask(
        String contractVersion, String requestId, Long learnerId, Long conversationId, Long courseId,
        Long anchorMediaId, Long courseVersion, Scope scope, List<Material> materials,
        String originalRequest, String goal, List<String> taskTypes, String executionStrategy,
        List<OutputSlot> deliverables, String sourcePolicy, Budget budget) {
    public PreparedStudyTask {
        materials = List.copyOf(materials); taskTypes = List.copyOf(taskTypes); deliverables = List.copyOf(deliverables);
    }
    public record Scope(String type, Long sectionId, List<Long> materialIds) {
        public Scope { materialIds = materialIds == null ? List.of() : List.copyOf(materialIds); }
    }
    public record Material(Long materialId, Long courseMaterialId, Long sectionId, String title, String version, String visualState) {}
    public record OutputSlot(String slotId, String kind, Map<String, Integer> constraints) {
        public OutputSlot { constraints = Map.copyOf(constraints); }
    }
    public record Budget(int inputTokens, int outputTokens, int generationCalls, int retrievalCalls, int maxMaterials,
                         long maxAccountedTokens, long activeLimitMs, long wallLimitMs, String runtimeVersion) {
        /** P4 tool-loop profile, deliberately separate from the currently enabled short workflows. */
        public static Budget boundedStudy(int input,int output,int materials) {
            return new Budget(input,output,8,6,materials,96000,120000,600000,"bounded-study-v1");
        }
        public static Budget standard(int input,int output,int generations,int retrievals,int materials) {
            return new Budget(input,output,generations,retrievals,materials,200000,120000,600000,"study-runtime-v1");
        }
    }

    public static PreparedStudyTask create(String id, Long learnerId, Long conversationId, Long courseId, Long anchor,
            Long courseVersion, Scope scope, List<Material> materials, String originalRequest, String goal,
            List<String> kinds, String sourcePolicy, Budget budget) {
        var slots = kinds.stream().map(kind -> new OutputSlot(id + ":" + kind + ":1", kind,
                "QUIZ".equals(kind) ? Map.of("maxQuestions", 3) : "ANSWER".equals(kind) ? Map.of("maxCitations", 5) : Map.of("maxPoints", 8))).toList();
        return new PreparedStudyTask("study-task-v1", id, learnerId, conversationId, courseId, anchor, courseVersion,
                scope, materials, originalRequest, goal, kinds.stream().map(k -> "NOTE".equals(k) ? "NOTES" : k).toList(),
                kinds.equals(List.of("ANSWER")) ? "QUICK_QA" : "SINGLE_VIDEO_NOTES", slots, sourcePolicy, budget);
    }
    public PreparedStudyTask forDeliverables(List<String> kinds) {
        return create(requestId, learnerId, conversationId, courseId, anchorMediaId, courseVersion, scope, materials,
                originalRequest, goal, kinds, sourcePolicy, budget);
    }
    public PreparedStudyTask inConversation(Long id) {
        return new PreparedStudyTask(contractVersion, requestId, learnerId, id, courseId, anchorMediaId, courseVersion,
                scope, materials, originalRequest, goal, taskTypes, executionStrategy, deliverables, sourcePolicy, budget);
    }
}

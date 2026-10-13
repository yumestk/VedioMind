package com.example.server.dto;

import java.util.List;

public record VideoAgentResult(
        String title,
        String coreConclusion,
        List<String> coreEvidenceRefs,
        List<KnowledgePoint> knowledgePoints,
        List<QuizItem> quiz,
        List<String> gaps
) {
    public VideoAgentResult {
        coreEvidenceRefs = copy(coreEvidenceRefs);
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        quiz = quiz == null ? List.of() : List.copyOf(quiz);
        gaps = copy(gaps);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record KnowledgePoint(String title, String explanation, List<String> evidenceRefs) {
        public KnowledgePoint {
            evidenceRefs = copy(evidenceRefs);
        }
    }

    public record QuizItem(String question, String answer, List<String> evidenceRefs) {
        public QuizItem {
            evidenceRefs = copy(evidenceRefs);
        }
    }
}

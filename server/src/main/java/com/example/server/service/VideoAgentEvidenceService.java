package com.example.server.service;

import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiPromptBudget;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class VideoAgentEvidenceService {
    private final VideoKnowledgeRetriever retriever;
    private final VideoEvidenceContextService contextService;
    private final AiPromptBudget budget;

    public VideoAgentEvidenceService(VideoKnowledgeRetriever retriever,
                                     VideoEvidenceContextService contextService, AiPromptBudget budget) {
        this.retriever = retriever;
        this.contextService = contextService;
        this.budget = budget;
    }

    public EvidenceBundle retrieve(VideoKnowledgeIndexService.IndexSnapshot index,
                                   String query, Runnable beforeEmbedding) {
        var hits = retriever.searchMultimodal(index, query, beforeEmbedding);
        var candidates = contextService.expand(index.context(), hits, true);
        // Three subtasks share at most half the input budget; the rest is for drafts and review instructions.
        var evidence = contextService.select(candidates, budget.inputLimit() / 6, 80, 20);
        if (!candidates.isEmpty() && evidence.isEmpty()) {
            throw new AiPromptBudget.PromptBudgetExceededException("No complete Agent evidence fits the input budget");
        }
        return new EvidenceBundle(hits, evidence);
    }

    public record EvidenceBundle(List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits,
                                 List<VideoAgentCitation> evidence) {
        public EvidenceBundle { hits = List.copyOf(hits); evidence = List.copyOf(evidence); }
    }
}

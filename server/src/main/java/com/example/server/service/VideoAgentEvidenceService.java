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
        return retrieve(index, query, beforeEmbedding, null);
    }

    public EvidenceBundle retrieve(VideoKnowledgeIndexService.IndexSnapshot index, String query,
                                   Runnable beforeEmbedding, java.time.Instant deadline) {
        var hits = deadline == null ? retriever.searchMultimodal(index, query, beforeEmbedding)
                : retriever.searchMultimodal(index, query, beforeEmbedding, deadline);
        var windows = hits.stream().map(hit -> contextService.windows(query,
                contextService.expand(index.context(), List.of(hit), true))).toList();
        // Notes-first has no executor drafts to reserve half the input for. Keep space for the actual prompt.
        var allocations = contextService.allocate(windows, Math.max(1024, budget.inputLimit() - 2048),
                hits.stream().<java.util.function.Predicate<List<VideoAgentCitation>>>map(hit -> values -> true).toList());
        var unique = new java.util.LinkedHashMap<String, VideoAgentCitation>();
        int longest = allocations.stream().mapToInt(List::size).max().orElse(0);
        for (int offset = 0; offset < longest; offset++) for (var allocation : allocations) {
            if (offset < allocation.size()) unique.putIfAbsent(allocation.get(offset).ref(), allocation.get(offset));
        }
        if (!hits.isEmpty() && unique.isEmpty()) {
            throw new AiPromptBudget.PromptBudgetExceededException("No complete Agent evidence fits the input budget");
        }
        return new EvidenceBundle(hits, List.copyOf(unique.values()));
    }

    public record EvidenceBundle(List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits,
                                 List<VideoAgentCitation> evidence) {
        public EvidenceBundle { hits = List.copyOf(hits); evidence = List.copyOf(evidence); }
    }
}

package com.example.server.service;

import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.impl.DeepSeekMessage;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class VideoAgentEvidenceService {
    static final int FINE_PROMPT_TARGET = 30_000;
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
        return retrieve(index, query, beforeEmbedding, deadline, goalPrefix(query));
    }

    EvidenceBundle retrieve(VideoKnowledgeIndexService.IndexSnapshot index, String query, Runnable beforeEmbedding,
                            java.time.Instant deadline, String prefix) {
        var hits = deadline == null ? retriever.searchMultimodal(index, query, beforeEmbedding)
                : retriever.searchMultimodal(index, query, beforeEmbedding, deadline);
        return new EvidenceBundle(hits, assemble(index, query, hits, prefix));
    }

    List<VideoAgentCitation> assemble(VideoKnowledgeIndexService.IndexSnapshot index, String query,
                                    List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits, String prefix) {
        boolean fine = index.processorVersion() == VideoKnowledgeIndexService.FINE_VERSION;
        var windows = hits.stream().map(hit -> {
            var ranked = contextService.windows(query, contextService.expand(index.context(), List.of(hit), true));
            if (!fine) return ranked;
            var prioritized = new java.util.ArrayList<>(ranked);
            for (int i = 0; i < prioritized.size(); i++) {
                if (prioritized.get(i).stream().anyMatch(e -> hit.startMs() <= e.startMs() && e.startMs() < hit.endMs())) {
                    prioritized.addFirst(prioritized.remove(i));
                    break;
                }
            }
            return prioritized;
        }).toList();
        var checks = hits.stream().<java.util.function.Predicate<List<VideoAgentCitation>>>map(hit -> values -> true).toList();
        List<List<VideoAgentCitation>> allocations;
        if (fine) {
            long fixed = promptTokens(prefix, List.of());
            long limit = Math.min(FINE_PROMPT_TARGET, budget.inputLimit()) - fixed;
            if (limit < 0) throw new AiPromptBudget.PromptBudgetExceededException("Notes prefix exceeds its input budget");
            allocations = contextService.allocate(windows, limit, checks, values -> promptTokens(prefix, values) - fixed);
        } else {
            allocations = contextService.allocate(windows, Math.max(1024, budget.inputLimit() - 2048), checks);
        }
        var unique = new java.util.LinkedHashMap<String, VideoAgentCitation>();
        int longest = allocations.stream().mapToInt(List::size).max().orElse(0);
        for (int offset = 0; offset < longest; offset++) for (var allocation : allocations) {
            if (offset < allocation.size()) unique.putIfAbsent(allocation.get(offset).ref(), allocation.get(offset));
        }
        if (!hits.isEmpty() && unique.isEmpty()) {
            throw new AiPromptBudget.PromptBudgetExceededException("No complete Agent evidence fits the input budget");
        }
        return List.copyOf(unique.values());
    }

    long promptTokens(String prefix, List<VideoAgentCitation> evidence) {
        return budget.estimate(VideoAgentWorkflowService.NOTES_PROMPT,
                List.of(new DeepSeekMessage("user", notesMaterial(prefix, evidence))));
    }

    static String notesMaterial(String prefix, List<VideoAgentCitation> evidence) {
        return prefix + "\n可用视频原文：\n" + VideoEvidenceContextService.formatEvidence(evidence);
    }

    static String goalPrefix(String goal) {
        return "学习目标：\n" + goal;
    }

    public record EvidenceBundle(List<VideoKnowledgeRetriever.KnowledgeSearchHit> hits,
                                 List<VideoAgentCitation> evidence) {
        public EvidenceBundle { hits = List.copyOf(hits); evidence = List.copyOf(evidence); }
    }
}

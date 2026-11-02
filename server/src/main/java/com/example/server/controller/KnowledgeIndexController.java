package com.example.server.controller;

import com.example.server.security.CurrentUserId;

import com.example.server.service.ai.AiRateLimitService;
import com.example.server.dto.AnalysisJobResponse;
import com.example.server.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/media/{mediaId}/knowledge-index")
public class KnowledgeIndexController {
    private final MediaService media;
    private final VideoKnowledgeIndexService index;
    private final KnowledgeIndexPreparationService preparation;
    private final AnalysisJobService jobs;
    private final AiRateLimitService rateLimits;

    public KnowledgeIndexController(MediaService media, VideoKnowledgeIndexService index,
                                    KnowledgeIndexPreparationService preparation, AnalysisJobService jobs,
                                    AiRateLimitService rateLimits) {
        this.rateLimits = rateLimits;
        this.media = media;
        this.index = index;
        this.preparation = preparation;
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<AnalysisJobResponse> prepare(@PathVariable Long mediaId, @CurrentUserId Long userId) {
        requireOwner(mediaId, userId);
        rateLimits.requireRequest(userId);
        try {
            index.requireReady(mediaId);
            return ResponseEntity.noContent().build();
        } catch (VideoKnowledgeIndexService.KnowledgeIndexNotReadyException exception) {
            return ResponseEntity.accepted().body(AnalysisJobResponse.from(preparation.prepare(mediaId, userId, true)));
        }
    }

    @GetMapping("/latest-job")
    public ResponseEntity<AnalysisJobResponse> latest(@PathVariable Long mediaId, @CurrentUserId Long userId) {
        requireOwner(mediaId, userId);
        return preparation.latest(mediaId).map(job -> ResponseEntity.ok(AnalysisJobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/jobs/{jobId}")
    public AnalysisJobResponse job(@PathVariable Long mediaId, @PathVariable String jobId, @CurrentUserId Long userId) {
        requireOwner(mediaId, userId);
        var job = jobs.findById(jobId).filter(value -> mediaId.equals(value.getMediaId())
                && "KNOWLEDGE_INDEX".equals(value.getJobType())).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Index task not found"));
        return AnalysisJobResponse.from(job);
    }

    private void requireOwner(Long mediaId, Long userId) {
        try { media.requireOwnership(mediaId, userId); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage()); }
    }
}

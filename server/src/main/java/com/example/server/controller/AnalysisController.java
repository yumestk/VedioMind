package com.example.server.controller;

import com.example.server.dto.AnalysisJobResponse;
import com.example.server.entity.AnalysisJob;
import com.example.server.service.AnalysisJobService;
import com.example.server.service.AnalysisSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private final AnalysisSubmissionService submissionService;
    private final AnalysisJobService analysisJobService;

    public AnalysisController(
            AnalysisSubmissionService submissionService,
            AnalysisJobService analysisJobService
    ) {
        this.submissionService = submissionService;
        this.analysisJobService = analysisJobService;
    }

    @PostMapping("/media/{mediaId}")
    public ResponseEntity<AnalysisJobResponse> submit(@PathVariable Long mediaId) {
        try {
            AnalysisJob job = submissionService.submit(mediaId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisJobResponse.from(job));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @GetMapping("/jobs/{jobId}")
    public AnalysisJobResponse getJob(@PathVariable String jobId) {
        return analysisJobService.findById(jobId)
                .map(AnalysisJobResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis job not found"));
    }
}

package com.example.server.controller;

import com.example.server.dto.AnalysisJobResponse;
import com.example.server.security.CurrentUserId;
import com.example.server.service.MediaService;
import com.example.server.entity.AnalysisJob;
import com.example.server.service.AnalysisJobService;
import com.example.server.service.AnalysisSubmissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.MessagingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/analysis")
@Slf4j
public class AnalysisController {

    private final AnalysisSubmissionService submissionService;
    private final AnalysisJobService analysisJobService;
    private final MediaService mediaService;

    public AnalysisController(
            AnalysisSubmissionService submissionService,
            AnalysisJobService analysisJobService,
            MediaService mediaService
    ) {
        this.submissionService = submissionService;
        this.analysisJobService = analysisJobService;
        this.mediaService = mediaService;
    }

    @PostMapping("/media/{mediaId}")
    public ResponseEntity<AnalysisJobResponse> submit(@PathVariable Long mediaId, @CurrentUserId Long userId) {
        try {
            AnalysisJob job = submissionService.submit(mediaId, userId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(AnalysisJobResponse.from(job));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @GetMapping("/jobs/{jobId}")
    public AnalysisJobResponse getJob(@PathVariable String jobId, @CurrentUserId Long userId) {
        return analysisJobService.findById(jobId)
                .map(job -> { requireOwner(job.getMediaId(), userId); return AnalysisJobResponse.from(job); })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis job not found"));
    }

    @GetMapping("/media/{mediaId}/active-job")
    public ResponseEntity<AnalysisJobResponse> getActiveJob(@PathVariable Long mediaId, @CurrentUserId Long userId) {
        requireOwner(mediaId, userId);
        return analysisJobService.findLatestActive(mediaId)
                .map(job -> ResponseEntity.ok(AnalysisJobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private void requireOwner(Long mediaId, Long userId) {
        try { mediaService.requireOwnership(mediaId, userId); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"); }
    }

    @ExceptionHandler(MessagingException.class)
    public ProblemDetail handleRocketMqUnavailable(MessagingException exception) {
        log.warn("RocketMQ rejected the analysis job submission: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RocketMQ 暂时不可用，请确认 NameServer 和 Broker 已启动后重试。"
        );
        problem.setTitle("分析服务暂时不可用");
        problem.setProperty("code", "ROCKETMQ_UNAVAILABLE");
        return problem;
    }
}

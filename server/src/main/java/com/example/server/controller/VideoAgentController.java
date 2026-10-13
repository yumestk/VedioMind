package com.example.server.controller;

import com.example.server.dto.VideoAgentRunRequest;
import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.MediaService;
import com.example.server.service.VideoAgentRunService;
import com.example.server.service.VideoAgentSubmissionService;
import com.example.server.service.VideoKnowledgeIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.MessagingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping
@Slf4j
public class VideoAgentController {

    private final VideoAgentSubmissionService submissionService;
    private final VideoAgentRunService runService;
    private final MediaService mediaService;

    public VideoAgentController(
            VideoAgentSubmissionService submissionService,
            VideoAgentRunService runService,
            MediaService mediaService
    ) {
        this.submissionService = submissionService;
        this.runService = runService;
        this.mediaService = mediaService;
    }

    @PostMapping("/media/{mediaId}/agent-runs")
    public ResponseEntity<VideoAgentRunResponse> submit(
            @PathVariable Long mediaId,
            @RequestParam Long userId,
            @RequestBody VideoAgentRunRequest request
    ) {
        try {
            VideoAgentRun run = submissionService.submit(
                    mediaId, userId, request == null ? null : request.goal()
            );
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(VideoAgentRunResponse.from(run));
        } catch (VideoKnowledgeIndexService.KnowledgeIndexNotReadyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "长视频知识索引尚未就绪，请先完成 AI 分析", exception);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @GetMapping("/media/{mediaId}/agent-runs")
    public List<VideoAgentRunResponse> list(
            @PathVariable Long mediaId,
            @RequestParam Long userId
    ) {
        requireOwnership(mediaId, userId);
        return runService.list(mediaId);
    }

    @GetMapping("/agent-runs/{runId}")
    public VideoAgentRunResponse get(
            @PathVariable String runId,
            @RequestParam Long userId
    ) {
        VideoAgentRun run;
        try {
            run = runService.getRequired(runId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        requireOwnership(run.getMediaId(), userId);
        return VideoAgentRunResponse.from(run);
    }

    @ExceptionHandler(MessagingException.class)
    public ProblemDetail handleRocketMqUnavailable(MessagingException exception) {
        log.warn("RocketMQ rejected the Agent task submission: {}", exception.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RocketMQ 暂时不可用，请确认 NameServer 和 Broker 已启动后重试。"
        );
        problem.setTitle("学习 Agent 暂时不可用");
        problem.setProperty("code", "ROCKETMQ_UNAVAILABLE");
        return problem;
    }

    private void requireOwnership(Long mediaId, Long userId) {
        try {
            mediaService.requireOwnership(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}

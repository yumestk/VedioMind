package com.example.server.controller;

import com.example.server.security.CurrentUserId;

import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.VideoAgentRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping
public class VideoAgentController {

    private final VideoAgentRunService runService;

    public VideoAgentController(
            VideoAgentRunService runService
    ) {
        this.runService = runService;
    }

    @GetMapping("/agent-runs/{runId}")
    public VideoAgentRunResponse get(
            @PathVariable String runId,
            @CurrentUserId Long userId
    ) {
        VideoAgentRun run;
        try {
            run = runService.getOwned(runId, userId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        return VideoAgentRunResponse.from(run, runService.sourceAvailable(run));
    }

    @PostMapping("/agent-runs/{runId}/cancel")
    public VideoAgentRunResponse cancel(@PathVariable String runId,@CurrentUserId Long userId) {
        runService.cancel(runId,userId);return get(runId,userId);
    }
    @GetMapping("/agent-runs/{runId}/events")
    public java.util.List<java.util.Map<String,Object>> events(@PathVariable String runId,@CurrentUserId Long userId,
            @RequestParam(defaultValue="0") long after) {return runService.events(runId,userId,after);}
    @ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ProblemDetail invalid(IllegalArgumentException ignored) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid event cursor");
    }
    @ExceptionHandler(NoSuchElementException.class)
    public org.springframework.http.ProblemDetail missing(NoSuchElementException ignored) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,"Study run not found");
    }

}

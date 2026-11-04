package com.example.server.controller;

import com.example.server.security.CurrentUserId;

import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.VideoAgentRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
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

}

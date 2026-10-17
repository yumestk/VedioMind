package com.example.server.controller;

import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.MediaService;
import com.example.server.service.VideoAgentRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping
public class VideoAgentController {

    private final VideoAgentRunService runService;
    private final MediaService mediaService;

    public VideoAgentController(
            VideoAgentRunService runService,
            MediaService mediaService
    ) {
        this.runService = runService;
        this.mediaService = mediaService;
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

    private void requireOwnership(Long mediaId, Long userId) {
        try {
            mediaService.requireOwnership(mediaId, userId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}

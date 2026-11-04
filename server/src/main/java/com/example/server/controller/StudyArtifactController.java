package com.example.server.controller;

import com.example.server.security.CurrentUserId;
import com.example.server.service.StudyArtifactService;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class StudyArtifactController {
    private final StudyArtifactService artifacts;

    public StudyArtifactController(StudyArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping("/agent-runs/{runId}/artifacts")
    public List<StudyArtifactService.Artifact> list(
            @PathVariable String runId, @CurrentUserId Long userId) {
        return artifacts.list(runId, userId);
    }

    @PutMapping("/study-artifacts/{id}")
    public StudyArtifactService.Artifact edit(
            @PathVariable String id,
            @CurrentUserId Long userId,
            @RequestBody StudyArtifactService.Edit edit) {
        return artifacts.edit(id, userId, edit);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missing() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Note not found");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(
                error instanceof GovernanceException e && "ARTIFACT_WRITE_CONFLICT".equals(e.code())
                        ? HttpStatus.CONFLICT
                        : HttpStatus.BAD_REQUEST,
                error.getMessage());
    }
}

package com.example.server.dto;

import com.example.server.entity.AnalysisJob;

import java.time.LocalDateTime;

public record AnalysisJobResponse(
        String id,
        Long mediaId,
        String status,
        Integer progress,
        Integer retryCount,
        LocalDateTime nextAttemptAt,
        String errorMessage,
        String warningMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    public static AnalysisJobResponse from(AnalysisJob job) {
        return new AnalysisJobResponse(
                job.getId(),
                job.getMediaId(),
                job.getStatus(),
                job.getProgress(),
                job.getRetryCount(),
                job.getNextAttemptAt(),
                job.getErrorMessage(),
                job.getWarningMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}

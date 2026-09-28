package com.example.server.dto;

import com.example.server.entity.AnalysisJob;

import java.time.LocalDateTime;

public record AnalysisJobResponse(
        String id,
        Long mediaId,
        String status,
        Integer progress,
        Integer retryCount,
        String errorMessage,
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
                job.getErrorMessage(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt()
        );
    }
}

package com.example.server.dto;

import java.util.Objects;

public record AnalysisJobMessage(String jobId, Long mediaId) {
    public AnalysisJobMessage {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(mediaId, "mediaId must not be null");
    }
}

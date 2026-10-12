package com.example.server.dto;

import java.util.List;

public record MediaVisualEvidenceResponse(
        Long mediaId,
        String status,
        Integer frameCount,
        String errorMessage,
        List<VideoKeyframeResponse> frames
) {
    public MediaVisualEvidenceResponse {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}

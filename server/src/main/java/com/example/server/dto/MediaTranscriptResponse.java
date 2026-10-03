package com.example.server.dto;

import java.util.List;

public record MediaTranscriptResponse(
        Long mediaId,
        List<TranscriptSegmentResponse> segments
) {
}

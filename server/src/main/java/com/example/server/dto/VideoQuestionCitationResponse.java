package com.example.server.dto;

public record VideoQuestionCitationResponse(
        Long segmentId,
        Long startMs,
        Long endMs,
        String text
) {
    public static VideoQuestionCitationResponse from(TranscriptSegmentResponse segment) {
        return new VideoQuestionCitationResponse(
                segment.id(),
                segment.startMs(),
                segment.endMs(),
                segment.text()
        );
    }
}

package com.example.server.dto;

public record VideoQuestionCitationResponse(Long segmentId, Long startMs, Long endMs, String text,
        Long materialId, String contextVersion, String evidenceRef, String type, boolean sourceAvailable) {
    public VideoQuestionCitationResponse(Long segmentId, Long startMs, Long endMs, String text) {
        this(segmentId, startMs, endMs, text, null, null, null, "TRANSCRIPT", true);
    }
    public static VideoQuestionCitationResponse from(TranscriptSegmentResponse segment) {
        return new VideoQuestionCitationResponse(segment.id(), segment.startMs(), segment.endMs(), segment.text());
    }
}

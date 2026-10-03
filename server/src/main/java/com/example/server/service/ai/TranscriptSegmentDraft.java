package com.example.server.service.ai;

public record TranscriptSegmentDraft(
        long startMs,
        long endMs,
        String text
) {
    public TranscriptSegmentDraft {
        if (startMs < 0) {
            throw new IllegalArgumentException("Transcript start time must not be negative");
        }
        if (endMs < startMs) {
            throw new IllegalArgumentException("Transcript end time must not be before start time");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Transcript segment text must not be empty");
        }
        text = text.trim();
    }
}

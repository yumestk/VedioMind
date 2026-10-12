package com.example.server.service.context;

import java.util.List;

public record VideoContext(
        Long mediaId,
        String version,
        String transcriptSource,
        Modality transcript,
        Modality visual,
        List<TranscriptEvidence> transcriptEvidence,
        List<VisualEvidence> visualEvidence
) {
    public VideoContext {
        transcriptEvidence = List.copyOf(transcriptEvidence);
        visualEvidence = List.copyOf(visualEvidence);
    }

    public record Modality(
            String state,
            int revision,
            int totalCount,
            int usableCount,
            String message
    ) {
    }

    public record TranscriptEvidence(
            Long id,
            long startMs,
            long endMs,
            String text
    ) {
    }

    public record VisualEvidence(
            Long id,
            long timestampMs,
            String objectKey,
            String ocrStatus,
            Double ocrConfidence,
            String ocrText
    ) {
    }
}

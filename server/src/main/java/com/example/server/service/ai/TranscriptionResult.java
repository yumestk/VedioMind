package com.example.server.service.ai;

import java.util.List;
import java.util.stream.Collectors;

public record TranscriptionResult(List<TranscriptSegmentDraft> segments) {

    public TranscriptionResult {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("Speech recognition returned no transcript segments");
        }
        segments = List.copyOf(segments);
    }

    public String fullText() {
        return segments.stream()
                .map(TranscriptSegmentDraft::text)
                .collect(Collectors.joining("\n"));
    }
}

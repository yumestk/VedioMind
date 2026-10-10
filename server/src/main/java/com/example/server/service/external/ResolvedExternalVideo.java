package com.example.server.service.external;

import com.example.server.service.ai.TranscriptSegmentDraft;

import java.util.List;

public record ResolvedExternalVideo(
        String platform,
        String externalId,
        String title,
        String coverUrl,
        Long durationMs,
        String sourceUrl,
        String transcriptSource,
        String transcriptLanguage,
        List<TranscriptSegmentDraft> segments
) {
    public ResolvedExternalVideo {
        segments = List.copyOf(segments);
    }
}

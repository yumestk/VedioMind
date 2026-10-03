package com.example.server.dto;

import com.example.server.entity.TranscriptSegment;

public record TranscriptSegmentResponse(
        Long id,
        Integer index,
        Long startMs,
        Long endMs,
        String text
) {
    public static TranscriptSegmentResponse from(TranscriptSegment segment) {
        return new TranscriptSegmentResponse(
                segment.getId(),
                segment.getSegmentIndex(),
                segment.getStartMs(),
                segment.getEndMs(),
                segment.getText()
        );
    }
}

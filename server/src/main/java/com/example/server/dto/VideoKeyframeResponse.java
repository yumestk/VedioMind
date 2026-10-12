package com.example.server.dto;

public record VideoKeyframeResponse(
        Long id,
        Integer frameIndex,
        Long timestampMs,
        String imageUrl,
        String ocrStatus,
        String ocrText
) {
}

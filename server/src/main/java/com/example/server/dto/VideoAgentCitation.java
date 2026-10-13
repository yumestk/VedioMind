package com.example.server.dto;

public record VideoAgentCitation(
        String ref,
        String type,
        Long sourceId,
        long startMs,
        long endMs,
        String text
) {
}

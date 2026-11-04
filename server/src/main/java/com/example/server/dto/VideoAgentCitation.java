package com.example.server.dto;

public record VideoAgentCitation(
        String ref,
        String type,
        Long sourceId,
        long startMs,
        long endMs,
        String text,
        Long materialId,
        String contextVersion
) {
    /** Local single-video evidence; its owning task supplies the material namespace at the course boundary. */
    public VideoAgentCitation(String ref, String type, Long sourceId, long startMs, long endMs, String text) {
        this(ref, type, sourceId, startMs, endMs, text, null, null);
    }
}

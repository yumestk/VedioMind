package com.example.server.dto;

public record InitiateMediaUploadResponse(
        String sessionId,
        long partSize
) {
}

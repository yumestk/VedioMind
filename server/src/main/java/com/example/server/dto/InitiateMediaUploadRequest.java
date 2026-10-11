package com.example.server.dto;

public record InitiateMediaUploadRequest(
        String filename,
        String mimeType,
        Long fileSize
) {
}

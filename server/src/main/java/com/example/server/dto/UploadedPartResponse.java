package com.example.server.dto;

public record UploadedPartResponse(
        int partNumber,
        String etag,
        long size
) {
}

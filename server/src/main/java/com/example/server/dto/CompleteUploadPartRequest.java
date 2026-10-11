package com.example.server.dto;

public record CompleteUploadPartRequest(
        Integer partNumber,
        String etag
) {
}

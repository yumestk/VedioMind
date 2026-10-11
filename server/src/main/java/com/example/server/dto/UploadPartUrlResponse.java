package com.example.server.dto;

public record UploadPartUrlResponse(
        int partNumber,
        String url
) {
}

package com.example.server.dto;

import java.util.List;

public record MediaUploadSessionResponse(
        String sessionId,
        String status,
        String filename,
        long fileSize,
        long partSize,
        Long mediaId,
        List<UploadedPartResponse> uploadedParts
) {
}

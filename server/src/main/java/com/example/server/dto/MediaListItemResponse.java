package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;

public record MediaListItemResponse(
        Long id,
        String filename,
        String status,
        String mimeType,
        Long fileSize,
        String coverUrl,
        LocalDateTime uploadTime
) {
    public static MediaListItemResponse from(MediaFile media) {
        return new MediaListItemResponse(
                media.getId(),
                media.getFilename(),
                media.getStatus(),
                media.getMimeType(),
                media.getFileSize(),
                media.getCoverUrl(),
                media.getUploadTime()
        );
    }
}

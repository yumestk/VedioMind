package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;

public record MediaDetailResponse(
        Long id,
        String filename,
        String status,
        String mimeType,
        Long fileSize,
        String coverUrl,
        String playbackUrl,
        String transcript,
        String summary,
        LocalDateTime uploadTime
) {
    public static MediaDetailResponse from(MediaFile media, String playbackUrl) {
        return new MediaDetailResponse(
                media.getId(),
                media.getFilename(),
                media.getStatus(),
                media.getMimeType(),
                media.getFileSize(),
                media.getCoverUrl(),
                playbackUrl,
                media.getTranscriptText(),
                media.getAiSummary(),
                media.getUploadTime()
        );
    }
}

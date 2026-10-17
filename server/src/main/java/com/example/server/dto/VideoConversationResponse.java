package com.example.server.dto;

import com.example.server.entity.VideoConversation;

import java.time.LocalDateTime;

public record VideoConversationResponse(
        Long id,
        Long mediaId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static VideoConversationResponse from(VideoConversation conversation) {
        return new VideoConversationResponse(
                conversation.getId(),
                conversation.getMediaId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}

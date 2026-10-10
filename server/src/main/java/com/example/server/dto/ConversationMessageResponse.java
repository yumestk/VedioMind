package com.example.server.dto;

import com.example.server.entity.VideoMessage;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationMessageResponse(
        Long id,
        String role,
        String content,
        List<VideoQuestionCitationResponse> citations,
        LocalDateTime createdAt
) {
    public ConversationMessageResponse {
        citations = List.copyOf(citations);
    }

    public static ConversationMessageResponse from(
            VideoMessage message,
            List<VideoQuestionCitationResponse> citations
    ) {
        return new ConversationMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citations,
                message.getCreatedAt()
        );
    }
}

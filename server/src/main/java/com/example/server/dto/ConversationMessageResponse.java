package com.example.server.dto;

import com.example.server.entity.VideoMessage;

import java.time.LocalDateTime;
import java.util.List;

public record ConversationMessageResponse(
        Long id,
        String messageType,
        String content,
        List<VideoQuestionCitationResponse> citations,
        VideoAgentRunResponse agentRun,
        LocalDateTime createdAt
) {
    public ConversationMessageResponse {
        citations = List.copyOf(citations);
    }

    public static ConversationMessageResponse from(
            VideoMessage message,
            List<VideoQuestionCitationResponse> citations,
            VideoAgentRunResponse agentRun
    ) {
        return new ConversationMessageResponse(
                message.getId(),
                message.getMessageType(),
                message.getContent(),
                citations,
                agentRun,
                message.getCreatedAt()
        );
    }
}

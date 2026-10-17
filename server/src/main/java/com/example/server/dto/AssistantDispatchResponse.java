package com.example.server.dto;

public record AssistantDispatchResponse(
        String executionMode,
        String routedBy,
        String reason,
        VideoConversationResponse conversation,
        ConversationMessagesResponse timeline
) {
    public static AssistantDispatchResponse of(
            String executionMode,
            String routedBy,
            String reason,
            VideoConversationResponse conversation,
            ConversationMessagesResponse timeline
    ) {
        return new AssistantDispatchResponse(
                executionMode, routedBy, reason, conversation, timeline
        );
    }
}

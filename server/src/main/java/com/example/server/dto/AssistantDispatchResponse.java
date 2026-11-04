package com.example.server.dto;

public record AssistantDispatchResponse(
        String executionMode,
        String routedBy,
        String reason,
        VideoConversationResponse conversation,
        ConversationMessagesResponse timeline,
        String preparationStatus,
        PreparedStudyTask task
) {
}

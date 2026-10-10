package com.example.server.dto;

public record ConversationTurnResponse(
        VideoConversationResponse conversation,
        ConversationMessageResponse userMessage,
        ConversationMessageResponse assistantMessage
) {
}

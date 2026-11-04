package com.example.server.dto;

import java.util.List;

public record ConversationMessagesResponse(
        Long conversationId,
        boolean sourceAvailable,
        List<ConversationMessageResponse> messages
) {
    public ConversationMessagesResponse {
        messages = List.copyOf(messages);
    }
}

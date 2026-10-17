package com.example.server.dto;

public record AssistantDispatchRequest(
        String content,
        String mode,
        Long conversationId
) {
}

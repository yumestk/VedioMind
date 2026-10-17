package com.example.server.dto;

public record VideoAgentRunMessage(
        String runId,
        Long mediaId,
        Long conversationId,
        String goal,
        String contextVersion
) {
}

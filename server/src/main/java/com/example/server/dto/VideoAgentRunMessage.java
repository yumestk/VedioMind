package com.example.server.dto;

public record VideoAgentRunMessage(
        String runId,
        Long mediaId,
        Long conversationId,
        Long learnerId,
        Long courseId,
        String goal,
        String contextVersion,
        PreparedStudyTask preparedTask
) {
}

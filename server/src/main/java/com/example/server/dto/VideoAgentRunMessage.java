package com.example.server.dto;

public record VideoAgentRunMessage(String runId, Long mediaId, String goal, String contextVersion) {
}

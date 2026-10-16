package com.example.server.dto;

public record AssistantDispatchResponse(
        String executionMode,
        String routedBy,
        String reason,
        ConversationTurnResponse conversationTurn,
        VideoAgentRunResponse agentRun
) {
    public static AssistantDispatchResponse quick(
            String routedBy,
            String reason,
            ConversationTurnResponse turn
    ) {
        return new AssistantDispatchResponse("QUICK", routedBy, reason, turn, null);
    }

    public static AssistantDispatchResponse deep(
            String routedBy,
            String reason,
            VideoAgentRunResponse run
    ) {
        return new AssistantDispatchResponse("DEEP", routedBy, reason, null, run);
    }
}

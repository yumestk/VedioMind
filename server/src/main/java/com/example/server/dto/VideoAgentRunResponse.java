package com.example.server.dto;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.VideoAgentRun;

import java.time.LocalDateTime;
import java.util.List;

public record VideoAgentRunResponse(
        String id,
        Long mediaId,
        String goal,
        String contextVersion,
        String status,
        Integer progress,
        Integer retryCount,
        LocalDateTime nextAttemptAt,
        Integer generationCalls,
        Integer retrievalCalls,
        Integer inputTokens,
        Integer outputTokens,
        Boolean tokenUsageKnown,
        VideoAgentResult result,
        List<VideoAgentCitation> citations,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    public static VideoAgentRunResponse from(VideoAgentRun run) {
        VideoAgentResult result = run.getResultJson() == null
                ? null
                : JSON.parseObject(run.getResultJson(), VideoAgentResult.class);
        List<VideoAgentCitation> citations = run.getCitationsJson() == null
                ? List.of()
                : JSON.parseArray(run.getCitationsJson(), VideoAgentCitation.class);
        return new VideoAgentRunResponse(
                run.getId(), run.getMediaId(), run.getGoal(), run.getContextVersion(),
                run.getStatus(), run.getProgress(), run.getRetryCount(), run.getNextAttemptAt(),
                run.getGenerationCalls(), run.getRetrievalCalls(), run.getInputTokens(),
                run.getOutputTokens(), run.getTokenUsageKnown(), result, citations,
                run.getErrorMessage(),
                run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt()
        );
    }
}

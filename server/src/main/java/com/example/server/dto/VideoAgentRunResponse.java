package com.example.server.dto;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.VideoAgentRun;

import java.time.LocalDateTime;
import java.util.List;

public record VideoAgentRunResponse(
        String id,
        Long mediaId,
        Long conversationId,
        Long courseId,
        boolean sourceAvailable,
        String goal,
        String contextVersion,
        String status,
        boolean terminal,
        String phase,
        String stopCode,
        Long executionEpoch,
        long activeElapsedMs,
        long waitingMs,
        Long activeLimitMs,
        LocalDateTime wallDeadlineAt,
        Long accountedTokens,
        Integer unknownCalls,
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
    public static VideoAgentRunResponse from(VideoAgentRun run, boolean sourceAvailable) {
        VideoAgentResult result = run.getResultJson() == null
                ? null
                : JSON.parseObject(run.getResultJson(), VideoAgentResult.class);
        List<VideoAgentCitation> citations = run.getCitationsJson() == null
                ? List.of()
                : JSON.parseArray(run.getCitationsJson(), VideoAgentCitation.class);
        return new VideoAgentRunResponse(
                run.getId(), run.getMediaId(), run.getConversationId(), run.getCourseId(), sourceAvailable, run.getGoal(), run.getContextVersion(),
                run.getStatus(),com.example.server.entity.StudyRunStatus.terminal(run.getStatus()),run.getPhase(),run.getStopCode(),run.getExecutionEpoch(),
                elapsed(run),waiting(run),run.getActiveLimitMs(),run.getDeadlineAt(),run.getAccountedTokens(),run.getUnknownCalls(),run.getProgress(), run.getRetryCount(), run.getNextAttemptAt(),
                run.getGenerationCalls(), run.getRetrievalCalls(), run.getInputTokens(),
                run.getOutputTokens(), run.getTokenUsageKnown(), result, sourceAvailable ? citations : List.of(),
                run.getErrorMessage(),
                run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt()
        );
    }
    private static long elapsed(VideoAgentRun run) {
        long remaining=run.getActiveRemainingMs()==null?120000:run.getActiveRemainingMs();
        if(run.getActiveStartedAt()!=null)remaining=Math.max(0,remaining-java.time.Duration.between(run.getActiveStartedAt(),LocalDateTime.now()).toMillis());
        return Math.max(0,(run.getActiveLimitMs()==null?120000:run.getActiveLimitMs())-remaining);
    }
    private static long waiting(VideoAgentRun run) {
        return (run.getWaitingMs()==null?0:run.getWaitingMs())+(run.getWaitStartedAt()==null?0:Math.max(0,java.time.Duration.between(run.getWaitStartedAt(),LocalDateTime.now()).toMillis()));
    }

}

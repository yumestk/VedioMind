package com.example.server.service;

import com.example.server.dto.AssistantDispatchRequest;
import com.example.server.dto.AssistantDispatchResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.VideoAgentRunResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.AiRateLimitService;
import org.springframework.stereotype.Service;

@Service
public class AssistantDispatchService {

    private final MediaService mediaService;
    private final AssistantModeRouter modeRouter;
    private final VideoConversationService conversationService;
    private final VideoAgentSubmissionService agentSubmissionService;
    private final VideoAgentRunService agentRunService;
    private final AiRateLimitService rateLimitService;

    public AssistantDispatchService(
            MediaService mediaService,
            AssistantModeRouter modeRouter,
            VideoConversationService conversationService,
            VideoAgentSubmissionService agentSubmissionService,
            VideoAgentRunService agentRunService,
            AiRateLimitService rateLimitService
    ) {
        this.mediaService = mediaService;
        this.modeRouter = modeRouter;
        this.conversationService = conversationService;
        this.agentSubmissionService = agentSubmissionService;
        this.agentRunService = agentRunService;
        this.rateLimitService = rateLimitService;
    }

    public AssistantDispatchResponse dispatch(Long mediaId, Long userId, AssistantDispatchRequest request) {
        String content = normalizeContent(request == null ? null : request.content());
        mediaService.requireOwnership(mediaId, userId);
        boolean studyMode = mediaService.isStudyMode(mediaId, userId);
        rateLimitService.requireRequest(userId);

        AssistantModeRouter.Decision decision = modeRouter.route(
                request.mode(), content, studyMode, hasText(request.sourceAgentRunId())
        );
        if ("DEEP".equals(decision.mode())) {
            if (agentRunService.findLatestActive(mediaId).isPresent()) {
                throw new AgentRunBusyException("当前视频已有正在执行的深度任务");
            }
            VideoAgentRun run = agentSubmissionService.submit(mediaId, userId, content);
            return AssistantDispatchResponse.deep(
                    decision.routedBy(), decision.reason(), VideoAgentRunResponse.from(run)
            );
        }

        Long conversationId = request.conversationId();
        if (conversationId == null) {
            VideoConversationResponse conversation = conversationService.create(
                    mediaId, userId, request.sourceAgentRunId()
            );
            conversationId = conversation.id();
        }
        ConversationTurnResponse turn = conversationService.ask(
                mediaId, conversationId, userId, content
        );
        return AssistantDispatchResponse.quick(decision.routedBy(), decision.reason(), turn);
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Assistant message must not be empty");
        }
        String normalized = content.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("Assistant message must not exceed 500 characters");
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static class AgentRunBusyException extends RuntimeException {
        public AgentRunBusyException(String message) {
            super(message);
        }
    }
}

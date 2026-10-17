package com.example.server.service;

import com.example.server.dto.AssistantDispatchRequest;
import com.example.server.dto.AssistantDispatchResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.VideoConversationResponse;
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

        Long requestedConversationId = request.conversationId();
        VideoConversationResponse conversation = requestedConversationId == null
                ? null
                : conversationService.get(mediaId, requestedConversationId, userId);
        boolean followingAgentRun = conversation != null
                && conversationService.hasCompletedAgentRun(conversation.id());
        AssistantModeRouter.Decision decision = modeRouter.route(
                request.mode(), content, studyMode, followingAgentRun
        );
        if ("DEEP".equals(decision.mode())) {
            if (agentRunService.findLatestActive(mediaId).isPresent()) {
                throw new AgentRunBusyException("当前视频已有正在执行的深度任务");
            }
            if (conversation == null) {
                conversation = conversationService.create(mediaId, userId);
            }
            agentSubmissionService.submit(mediaId, userId, conversation.id(), content);
        } else {
            if (conversation == null) {
                conversation = conversationService.create(mediaId, userId);
            }
            ConversationTurnResponse turn = conversationService.ask(
                    mediaId, conversation.id(), userId, content
            );
            conversation = turn.conversation();
        }

        conversation = conversationService.get(mediaId, conversation.id(), userId);
        return AssistantDispatchResponse.of(
                decision.mode(), decision.routedBy(), decision.reason(), conversation,
                conversationService.messages(conversation.id(), userId)
        );
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

    public static class AgentRunBusyException extends RuntimeException {
        public AgentRunBusyException(String message) {
            super(message);
        }
    }
}

package com.example.server.service;

import com.example.server.dto.AssistantDispatchRequest;
import com.example.server.dto.AssistantDispatchResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.service.ai.AiRateLimitService;

import org.springframework.stereotype.Service;

@Service
public class AssistantDispatchService {

    private final StudyTaskPreparationService tasks;
    private final MediaService mediaService;
    private final AssistantModeRouter modeRouter;
    private final VideoConversationService conversationService;
    private final VideoAgentSubmissionService agentSubmissionService;
    private final VideoAgentRunService agentRunService;
    private final AiRateLimitService rateLimitService;
    private final com.example.server.service.ai.AiCallLedger ledger;

    public AssistantDispatchService(
            MediaService mediaService,
            AssistantModeRouter modeRouter,
            VideoConversationService conversationService,
            VideoAgentSubmissionService agentSubmissionService,
            VideoAgentRunService agentRunService,
            AiRateLimitService rateLimitService,
            StudyTaskPreparationService tasks,
            com.example.server.service.ai.AiCallLedger ledger) {
        this.tasks = tasks;
        this.ledger = ledger;
        this.mediaService = mediaService;
        this.modeRouter = modeRouter;
        this.conversationService = conversationService;
        this.agentSubmissionService = agentSubmissionService;
        this.agentRunService = agentRunService;
        this.rateLimitService = rateLimitService;
    }

    public AssistantDispatchResponse dispatch(
            Long mediaId, Long userId, AssistantDispatchRequest request) {
        String content = normalizeContent(request == null ? null : request.content());
        mediaService.requireReadable(mediaId, request.courseId(), userId);
        boolean studyMode = mediaService.isStudyMode(mediaId, userId);

        Long requestedConversationId = request.conversationId();
        VideoConversationResponse conversation =
                requestedConversationId == null
                        ? null
                        : conversationService.get(mediaId, requestedConversationId, userId);
        if (conversation != null
                && !java.util.Objects.equals(conversation.courseId(), request.courseId()))
            throw new java.util.NoSuchElementException(
                    "Conversation does not belong to this course context");
        com.example.server.dto.PreparedStudyTask task;
        try {
            task =
                    tasks.prepare(
                            mediaId,
                            userId,
                            requestedConversationId,
                            request.courseId(),
                            request.scope(),
                            content);
        } catch (StudyTaskPreparationService.NeedsInputException missing) {
            return new AssistantDispatchResponse(
                    null,
                    "PREPARATION",
                    missing.getMessage(),
                    conversation,
                    null,
                    "NEEDS_INPUT",
                    null);
        }
        if (!"VIDEO".equals(task.scope().type())
                && !"QUICK".equalsIgnoreCase(request.mode())
                && VideoAgentWorkflowService.requestsQuiz(task.goal()))
            return new AssistantDispatchResponse(
                    null,
                    "PREPARATION",
                    "跨视频测验将在下一阶段接入，请先只生成笔记",
                    conversation,
                    null,
                    "NEEDS_INPUT",
                    null);
        rateLimitService.requireRequest(userId);
        try (var context = ledger.open(task)) {
            boolean followingAgentRun =
                    conversation != null
                            && conversationService.hasCompletedAgentRun(conversation.id());
            AssistantModeRouter.Decision decision =
                    modeRouter.route(request.mode(), content, studyMode, followingAgentRun);
            try {
                task = tasks.forMode(task, decision.mode());
            } catch (StudyTaskPreparationService.NeedsInputException missing) {
                return new AssistantDispatchResponse(
                        null,
                        "PREPARATION",
                        missing.getMessage(),
                        conversation,
                        null,
                        "NEEDS_INPUT",
                        null);
            }
            ledger.selectProfile(task);
            try (var selected =
                    com.example.server.service.ai.StudyExecutionContext.open(task, null, 0)) {
                if ("DEEP".equals(decision.mode())) {
                    if (conversation == null) {
                        conversation =
                                conversationService.create(mediaId, userId, request.courseId());
                    }
                    if (agentRunService.findLatestActive(conversation.id(), userId).isPresent())
                        throw new AgentRunBusyException("当前对话已有正在执行的深度任务");
                    agentSubmissionService.submit(task.inConversation(conversation.id()));
                } else {
                    if (conversation == null) {
                        conversation =
                                conversationService.create(mediaId, userId, request.courseId());
                    }
                    ConversationTurnResponse turn =
                            conversationService.askPrepared(task.inConversation(conversation.id()));
                    conversation = turn.conversation();
                }

                conversation = conversationService.get(mediaId, conversation.id(), userId);
                return new AssistantDispatchResponse(
                        decision.mode(),
                        decision.routedBy(),
                        decision.reason(),
                        conversation,
                        conversationService.messages(conversation.id(), userId),
                        "READY",
                        task.inConversation(conversation.id()));
            }
        }
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

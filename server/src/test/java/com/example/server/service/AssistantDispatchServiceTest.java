package com.example.server.service;

import com.example.server.dto.AssistantDispatchRequest;
import com.example.server.dto.AssistantDispatchResponse;
import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.AiRateLimitService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantDispatchServiceTest {

    private final MediaService mediaService = mock(MediaService.class);
    private final AssistantModeRouter modeRouter = mock(AssistantModeRouter.class);
    private final VideoConversationService conversationService = mock(VideoConversationService.class);
    private final VideoAgentSubmissionService agentSubmissionService = mock(VideoAgentSubmissionService.class);
    private final VideoAgentRunService agentRunService = mock(VideoAgentRunService.class);
    private final AiRateLimitService rateLimitService = mock(AiRateLimitService.class);
    private final AssistantDispatchService service = new AssistantDispatchService(
            mediaService,
            modeRouter,
            conversationService,
            agentSubmissionService,
            agentRunService,
            rateLimitService
    );

    @Test
    void createsAConversationAndDispatchesAQuickQuestion() {
        AssistantDispatchRequest request = new AssistantDispatchRequest(
                " 主讲人是谁？ ", "AUTO", null
        );
        VideoConversationResponse conversation = mock(VideoConversationResponse.class);
        ConversationTurnResponse turn = mock(ConversationTurnResponse.class);
        ConversationMessagesResponse timeline = mock(ConversationMessagesResponse.class);
        when(mediaService.isStudyMode(42L, 3L)).thenReturn(true);
        when(modeRouter.route("AUTO", "主讲人是谁？", true, false)).thenReturn(
                new AssistantModeRouter.Decision("QUICK", "RULE", "普通问题")
        );
        when(conversation.id()).thenReturn(7L);
        when(conversationService.create(42L, 3L)).thenReturn(conversation);
        when(conversationService.ask(42L, 7L, 3L, "主讲人是谁？")).thenReturn(turn);
        when(turn.conversation()).thenReturn(conversation);
        when(conversationService.get(42L, 7L, 3L)).thenReturn(conversation);
        when(conversationService.messages(7L, 3L)).thenReturn(timeline);

        AssistantDispatchResponse response = service.dispatch(42L, 3L, request);

        assertThat(response.executionMode()).isEqualTo("QUICK");
        assertThat(response.conversation()).isSameAs(conversation);
        assertThat(response.timeline()).isSameAs(timeline);
        verify(rateLimitService).requireRequest(3L);
    }

    @Test
    void dispatchesADeepTaskToTheExistingAgentSubmissionService() {
        AssistantDispatchRequest request = new AssistantDispatchRequest(
                "整理整节课", "AUTO", null
        );
        VideoConversationResponse conversation = mock(VideoConversationResponse.class);
        ConversationMessagesResponse timeline = mock(ConversationMessagesResponse.class);
        VideoAgentRun run = new VideoAgentRun();
        run.setId("run-2");
        run.setMediaId(42L);
        run.setGoal("整理整节课");
        run.setStatus("QUEUED");
        when(mediaService.isStudyMode(42L, 3L)).thenReturn(true);
        when(modeRouter.route("AUTO", "整理整节课", true, false)).thenReturn(
                new AssistantModeRouter.Decision("DEEP", "RULE", "结构化任务")
        );
        when(agentRunService.findLatestActive(42L)).thenReturn(Optional.empty());
        when(conversation.id()).thenReturn(7L);
        when(conversationService.create(42L, 3L)).thenReturn(conversation);
        when(conversationService.get(42L, 7L, 3L)).thenReturn(conversation);
        when(conversationService.messages(7L, 3L)).thenReturn(timeline);
        when(agentSubmissionService.submit(42L, 3L, 7L, "整理整节课")).thenReturn(run);

        AssistantDispatchResponse response = service.dispatch(42L, 3L, request);

        assertThat(response.executionMode()).isEqualTo("DEEP");
        assertThat(response.timeline()).isSameAs(timeline);
        verify(agentSubmissionService).submit(42L, 3L, 7L, "整理整节课");
        verify(rateLimitService).requireRequest(3L);
    }

    @Test
    void rejectsAnotherDeepTaskWhileOneIsRunning() {
        AssistantDispatchRequest request = new AssistantDispatchRequest(
                "整理整节课", "DEEP", null
        );
        when(mediaService.isStudyMode(42L, 3L)).thenReturn(true);
        when(modeRouter.route("DEEP", "整理整节课", true, false)).thenReturn(
                new AssistantModeRouter.Decision("DEEP", "EXPLICIT", "用户选择")
        );
        when(agentRunService.findLatestActive(42L)).thenReturn(Optional.of(new VideoAgentRun()));

        assertThatThrownBy(() -> service.dispatch(42L, 3L, request))
                .isInstanceOf(AssistantDispatchService.AgentRunBusyException.class);

        verify(agentSubmissionService, never()).submit(42L, 3L, 7L, "整理整节课");
    }
}

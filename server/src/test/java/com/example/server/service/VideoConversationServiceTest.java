package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.TranscriptSegmentResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.dto.VideoQuestionCitationResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.dto.VideoAgentResult;
import com.example.server.entity.VideoAgentRun;
import com.example.server.entity.VideoConversation;
import com.example.server.entity.VideoMessage;
import com.example.server.service.ai.impl.DeepSeekMessage;
import com.example.server.service.ai.AiRateLimitService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoConversationServiceTest {

    private final VideoConversationStore store = mock(VideoConversationStore.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final VideoQuestionService questionService = mock(VideoQuestionService.class);
    private final VideoKnowledgeRetriever knowledgeRetriever = mock(VideoKnowledgeRetriever.class);
    private final VideoAgentRunService agentRunService = mock(VideoAgentRunService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final AiRateLimitService rateLimitService = mock(AiRateLimitService.class);
    private final VideoConversationService service = new VideoConversationService(
            store,
            mediaService,
            questionService,
            knowledgeRetriever,
            agentRunService,
            redissonClient,
            rateLimitService
    );

    @Test
    void usesPersistedHistoryAndSavesTheCompletedTurn() throws InterruptedException {
        VideoConversation conversation = conversation();
        List<TranscriptSegmentResponse> segments = List.of(
                new TranscriptSegmentResponse(9L, 0, 1_000L, 2_000L, "字幕")
        );
        List<VideoMessage> history = List.of(
                message("USER", "上一问"),
                message("ASSISTANT", "上一答")
        );
        VideoQuestionResponse answer = new VideoQuestionResponse(
                "新回答",
                List.of(new VideoQuestionCitationResponse(9L, 1_000L, 2_000L, "字幕"))
        );
        ConversationTurnResponse saved = new ConversationTurnResponse(
                VideoConversationResponse.from(conversation),
                null,
                null
        );

        when(store.getRequired(7L)).thenReturn(conversation);
        when(redissonClient.getLock("lock:video-conversation:7")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaService.getTranscript(42L, 3L)).thenReturn(new MediaTranscriptResponse(42L, segments));
        when(store.listRecentMessages(7L, 12)).thenReturn(history);
        when(questionService.answer(eq("继续说说"), eq(segments), any(), eq(""))).thenReturn(answer);
        when(store.saveTurn(conversation, "继续说说", answer)).thenReturn(saved);

        assertThat(service.ask(7L, 3L, " 继续说说 ")).isSameAs(saved);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> context = ArgumentCaptor.forClass(List.class);
        verify(questionService).answer(eq("继续说说"), eq(segments), context.capture(), eq(""));
        verify(rateLimitService).requireRequest(3L);
        assertThat(context.getValue()).containsExactly(
                new DeepSeekMessage("user", "上一问"),
                new DeepSeekMessage("assistant", "上一答")
        );
        verify(store).saveTurn(conversation, "继续说说", answer);
        verify(knowledgeRetriever, never()).search(any(), any());
        verify(lock).unlock();
    }

    @Test
    void retrievesStudyEvidenceWithThePreviousTurnBeforeAnswering() throws InterruptedException {
        VideoConversation conversation = conversation();
        conversation.setSourceAgentRunId("run-1");
        VideoAgentRun run = completedRun();
        TranscriptSegmentResponse first = new TranscriptSegmentResponse(
                9L, 0, 1_000L, 2_000L, "第一段字幕"
        );
        TranscriptSegmentResponse second = new TranscriptSegmentResponse(
                10L, 1, 2_000L, 3_000L, "第二段字幕"
        );
        List<VideoMessage> history = List.of(
                message("USER", "介绍数据库"),
                message("ASSISTANT", "视频介绍了 MySQL 和 PostgreSQL")
        );
        VideoQuestionResponse answer = new VideoQuestionResponse("新回答", List.of());
        ConversationTurnResponse saved = new ConversationTurnResponse(
                VideoConversationResponse.from(conversation), null, null
        );

        when(store.getRequired(7L)).thenReturn(conversation);
        when(agentRunService.getRequired("run-1")).thenReturn(run);
        when(redissonClient.getLock("lock:video-conversation:7")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaService.getTranscript(42L, 3L)).thenReturn(
                new MediaTranscriptResponse(42L, List.of(first, second))
        );
        when(mediaService.isStudyMode(42L, 3L)).thenReturn(true);
        when(store.listRecentMessages(7L, 12)).thenReturn(history);
        when(knowledgeRetriever.search(eq(42L), argThat(query ->
                query.contains("深度学习任务结果：")
                        && query.contains("知识点 2：PostgreSQL")
                        && query.contains("上一答：视频介绍了 MySQL 和 PostgreSQL")
                        && query.endsWith("当前问题：第二个有什么优点？")
        ))).thenReturn(List.of(new VideoKnowledgeRetriever.KnowledgeSearchHit(
                0, 0, 300_000, "摘要", List.of("PostgreSQL"), 0.9,
                List.of(10L, 9L, 10L), List.of()
        )));
        when(questionService.answer(
                eq("第二个有什么优点？"), eq(List.of(second, first)), any(),
                argThat(context -> context.contains("知识点 2：PostgreSQL"))
        )).thenReturn(answer);
        when(store.saveTurn(conversation, "第二个有什么优点？", answer)).thenReturn(saved);

        assertThat(service.ask(7L, 3L, "第二个有什么优点？")).isSameAs(saved);

        verify(questionService).answer(
                eq("第二个有什么优点？"), eq(List.of(second, first)), any(),
                argThat(context -> context.contains("知识点 2：PostgreSQL"))
        );
        verify(store).saveTurn(conversation, "第二个有什么优点？", answer);
        verify(lock).unlock();
    }

    @Test
    void reportsStudyIndexAsNotReadyWithoutSavingAPartialTurn() throws InterruptedException {
        VideoConversation conversation = conversation();
        List<TranscriptSegmentResponse> segments = List.of(
                new TranscriptSegmentResponse(9L, 0, 1_000L, 2_000L, "字幕")
        );
        when(store.getRequired(7L)).thenReturn(conversation);
        when(redissonClient.getLock("lock:video-conversation:7")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaService.getTranscript(42L, 3L)).thenReturn(
                new MediaTranscriptResponse(42L, segments)
        );
        when(mediaService.isStudyMode(42L, 3L)).thenReturn(true);
        when(store.listRecentMessages(7L, 12)).thenReturn(List.of());
        when(knowledgeRetriever.search(42L, "当前问题：问题")).thenThrow(
                new VideoKnowledgeIndexService.KnowledgeIndexNotReadyException("not ready")
        );

        assertThatThrownBy(() -> service.ask(7L, 3L, "问题"))
                .isInstanceOf(VideoConversationService.ConversationNotReadyException.class)
                .hasMessageContaining("重新进行 AI 分析");

        verify(questionService, never()).answer(any(), any(), any(), anyString());
        verify(store, never()).saveTurn(any(), any(), any());
        verify(lock).unlock();
    }

    @Test
    void rejectsConcurrentQuestionsForTheSameConversation() throws InterruptedException {
        when(store.getRequired(7L)).thenReturn(conversation());
        when(redissonClient.getLock("lock:video-conversation:7")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> service.ask(7L, 3L, "问题"))
                .isInstanceOf(VideoConversationService.ConversationBusyException.class);

        verify(questionService, never()).answer(any(), any(), any(), anyString());
        verify(store, never()).saveTurn(any(), any(), any());
    }

    @Test
    void createsAConversationLinkedToACompletedRunFromTheSameVideo() {
        VideoAgentRun run = completedRun();
        VideoConversationResponse created = mock(VideoConversationResponse.class);
        when(agentRunService.getRequired("run-1")).thenReturn(run);
        when(store.create(42L, "run-1")).thenReturn(created);

        assertThat(service.create(42L, 3L, " run-1 ")).isSameAs(created);

        verify(store).create(42L, "run-1");
    }

    @Test
    void rejectsLinkingAnUnfinishedAgentRun() {
        VideoAgentRun run = completedRun();
        run.setStatus("CRITIQUING");
        when(agentRunService.getRequired("run-1")).thenReturn(run);

        assertThatThrownBy(() -> service.create(42L, 3L, "run-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed Agent run");

        verify(store, never()).create(any(), any());
    }

    private VideoConversation conversation() {
        VideoConversation conversation = new VideoConversation();
        conversation.setId(7L);
        conversation.setMediaId(42L);
        conversation.setTitle("对话");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }

    private VideoMessage message(String role, String content) {
        VideoMessage message = new VideoMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private VideoAgentRun completedRun() {
        VideoAgentRun run = new VideoAgentRun();
        run.setId("run-1");
        run.setMediaId(42L);
        run.setGoal("比较两种数据库");
        run.setStatus("SUCCEEDED");
        run.setResultJson(JSON.toJSONString(new VideoAgentResult(
                "数据库比较",
                "视频比较了两种数据库",
                List.of("TRANSCRIPT:9"),
                List.of(
                        new VideoAgentResult.KnowledgePoint(
                                "MySQL", "适合常见关系型业务", List.of("TRANSCRIPT:9")
                        ),
                        new VideoAgentResult.KnowledgePoint(
                                "PostgreSQL", "强调扩展能力", List.of("TRANSCRIPT:10")
                        )
                ),
                List.of(),
                List.of()
        )));
        return run;
    }
}

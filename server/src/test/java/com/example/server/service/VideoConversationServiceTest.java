package com.example.server.service;

import com.example.server.dto.ConversationTurnResponse;
import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.TranscriptSegmentResponse;
import com.example.server.dto.VideoConversationResponse;
import com.example.server.dto.VideoQuestionCitationResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.entity.VideoConversation;
import com.example.server.entity.VideoMessage;
import com.example.server.service.ai.impl.DeepSeekMessage;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoConversationServiceTest {

    private final VideoConversationStore store = mock(VideoConversationStore.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final VideoQuestionService questionService = mock(VideoQuestionService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final VideoConversationService service = new VideoConversationService(
            store,
            mediaService,
            questionService,
            redissonClient
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
        when(questionService.answer(eq("继续说说"), eq(segments), any())).thenReturn(answer);
        when(store.saveTurn(conversation, "继续说说", answer)).thenReturn(saved);

        assertThat(service.ask(7L, 3L, " 继续说说 ")).isSameAs(saved);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> context = ArgumentCaptor.forClass(List.class);
        verify(questionService).answer(eq("继续说说"), eq(segments), context.capture());
        assertThat(context.getValue()).containsExactly(
                new DeepSeekMessage("user", "上一问"),
                new DeepSeekMessage("assistant", "上一答")
        );
        verify(store).saveTurn(conversation, "继续说说", answer);
        verify(lock).unlock();
    }

    @Test
    void rejectsConcurrentQuestionsForTheSameConversation() throws InterruptedException {
        when(store.getRequired(7L)).thenReturn(conversation());
        when(redissonClient.getLock("lock:video-conversation:7")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> service.ask(7L, 3L, "问题"))
                .isInstanceOf(VideoConversationService.ConversationBusyException.class);

        verify(questionService, never()).answer(any(), any(), any());
        verify(store, never()).saveTurn(any(), any(), any());
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
}

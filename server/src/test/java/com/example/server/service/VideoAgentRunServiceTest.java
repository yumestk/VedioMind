package com.example.server.service;

import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoAgentRun;
import com.example.server.entity.VideoConversation;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoAgentRunMapper;
import com.example.server.mapper.VideoAgentStepMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoAgentRunServiceTest {

    private final VideoAgentRunMapper runMapper = mock(VideoAgentRunMapper.class);
    private final VideoAgentStepMapper stepMapper = mock(VideoAgentStepMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final VideoConversationStore conversationStore = mock(VideoConversationStore.class);
    private final VideoAgentRunService service = new VideoAgentRunService(
            runMapper, stepMapper, mediaFileMapper, conversationStore
    );

    @Test
    void createsTheRunAndTimelineMessagesInOneServiceTransaction() {
        VideoAgentRunMessage command = new VideoAgentRunMessage(
                "run-1", 42L, 7L, "整理整节课", "context-v1"
        );
        MediaFile media = new MediaFile();
        VideoConversation conversation = new VideoConversation();
        conversation.setId(7L);
        conversation.setMediaId(42L);
        when(mediaFileMapper.selectById(42L)).thenReturn(media);
        when(conversationStore.getRequired(7L)).thenReturn(conversation);

        service.createQueuedRun(command);

        ArgumentCaptor<VideoAgentRun> run = ArgumentCaptor.forClass(VideoAgentRun.class);
        verify(runMapper).insert(run.capture());
        assertThat(run.getValue().getId()).isEqualTo("run-1");
        assertThat(run.getValue().getStatus()).isEqualTo("QUEUED");
        verify(conversationStore).appendAgentRunRequest(
                eq(7L), eq("run-1"), eq("整理整节课"), any(LocalDateTime.class)
        );
    }

    @Test
    void ignoresARepeatedRocketMqLocalTransactionCallback() {
        VideoAgentRun existing = new VideoAgentRun();
        when(runMapper.selectById("run-1")).thenReturn(existing);
        VideoAgentRunMessage command = new VideoAgentRunMessage(
                "run-1", 42L, 7L, "整理整节课", "context-v1"
        );

        service.createQueuedRun(command);

        verify(runMapper, never()).insert(any(VideoAgentRun.class));
        verify(conversationStore, never()).appendAgentRunRequest(
                any(), any(), any(), any()
        );
    }

    @Test
    void rejectsAConversationFromAnotherVideo() {
        VideoConversation conversation = new VideoConversation();
        conversation.setId(7L);
        conversation.setMediaId(99L);
        when(mediaFileMapper.selectById(42L)).thenReturn(new MediaFile());
        when(conversationStore.getRequired(7L)).thenReturn(conversation);
        VideoAgentRunMessage command = new VideoAgentRunMessage(
                "run-1", 42L, 7L, "整理整节课", "context-v1"
        );

        assertThatThrownBy(() -> service.createQueuedRun(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conversation");

        verify(runMapper, never()).insert(any(VideoAgentRun.class));
    }
}

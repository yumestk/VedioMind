package com.example.server.service;

import com.example.server.dto.ConversationMessagesResponse;
import com.example.server.entity.VideoAgentRun;
import com.example.server.entity.VideoConversation;
import com.example.server.entity.VideoMessage;
import com.example.server.mapper.VideoAgentRunMapper;
import com.example.server.mapper.VideoConversationMapper;
import com.example.server.mapper.VideoMessageCitationMapper;
import com.example.server.mapper.VideoMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoConversationStoreTest {

    private final VideoConversationMapper conversationMapper = mock(VideoConversationMapper.class);
    private final VideoMessageMapper messageMapper = mock(VideoMessageMapper.class);
    private final VideoMessageCitationMapper citationMapper = mock(VideoMessageCitationMapper.class);
    private final VideoAgentRunMapper runMapper = mock(VideoAgentRunMapper.class);
    private final VideoConversationStore store = new VideoConversationStore(
            conversationMapper, messageMapper, citationMapper, runMapper
    );

    @Test
    void hydratesAnAgentRunMessageWithoutChangingChatMessages() {
        VideoMessage user = message(1L, "USER", null);
        VideoMessage task = message(2L, "AGENT_RUN", "run-1");
        VideoAgentRun run = new VideoAgentRun();
        run.setId("run-1");
        run.setMediaId(42L);
        run.setGoal("整理整节课");
        run.setStatus("QUEUED");
        when(messageMapper.selectList(any())).thenReturn(List.of(user, task));
        when(citationMapper.selectList(any())).thenReturn(List.of());
        when(runMapper.selectBatchIds(any())).thenReturn(List.of(run));

        ConversationMessagesResponse timeline = store.listMessages(7L);

        assertThat(timeline.messages()).hasSize(2);
        assertThat(timeline.messages().get(0).messageType()).isEqualTo("USER");
        assertThat(timeline.messages().get(0).agentRun()).isNull();
        assertThat(timeline.messages().get(1).agentRun().id()).isEqualTo("run-1");
    }

    @Test
    void appendsAUserMessageAndAgentTaskCardTogether() {
        VideoConversation conversation = new VideoConversation();
        conversation.setId(7L);
        conversation.setMediaId(42L);
        conversation.setTitle("新对话");
        when(conversationMapper.selectById(7L)).thenReturn(conversation);

        store.appendAgentRunRequest(7L, "run-1", "整理整节课", LocalDateTime.now());

        ArgumentCaptor<VideoMessage> messages = ArgumentCaptor.forClass(VideoMessage.class);
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(VideoMessage::getMessageType)
                .containsExactly("USER", "AGENT_RUN");
        assertThat(messages.getAllValues().get(1).getAgentRunId()).isEqualTo("run-1");
        verify(conversationMapper).updateById(conversation);
    }

    private VideoMessage message(Long id, String type, String runId) {
        VideoMessage message = new VideoMessage();
        message.setId(id);
        message.setConversationId(7L);
        message.setMessageType(type);
        message.setContent("内容");
        message.setAgentRunId(runId);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}

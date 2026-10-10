package com.example.server.service;

import com.example.server.dto.TranscriptSegmentResponse;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.ai.impl.DeepSeekMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoQuestionServiceTest {

    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final VideoQuestionService service = new VideoQuestionService(chatClient);
    private final List<TranscriptSegmentResponse> segments = List.of(
            new TranscriptSegmentResponse(11L, 0, 1_000L, 3_000L, "第一段字幕"),
            new TranscriptSegmentResponse(12L, 1, 3_000L, 6_000L, "第二段字幕")
    );

    @Test
    void mapsValidatedSegmentIdsToServerOwnedCitations() {
        when(chatClient.chat(anyString(), anyList())).thenReturn("""
                ```json
                {"answer":"这是回答","segmentIds":[12,11,12]}
                ```
                """);

        List<DeepSeekMessage> history = List.of(
                new DeepSeekMessage("user", "前一个问题"),
                new DeepSeekMessage("assistant", "前一个回答")
        );
        VideoQuestionResponse response = service.answer("视频讲了什么？", segments, history);

        assertThat(response.answer()).isEqualTo("这是回答");
        assertThat(response.citations()).extracting("segmentId").containsExactly(12L, 11L);
        assertThat(response.citations().getFirst().startMs()).isEqualTo(3_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(chatClient).chat(anyString(), messages.capture());
        assertThat(messages.getValue()).hasSize(3);
        assertThat(messages.getValue().subList(0, 2)).isEqualTo(history);
        assertThat(messages.getValue().getLast().content()).contains(
                "\"segmentId\":11",
                "\"text\":\"第一段字幕\"",
                "\"segmentId\":12"
        );
    }

    @Test
    void rejectsCitationsThatAreNotInTheStoredTranscript() {
        when(chatClient.chat(anyString(), anyList()))
                .thenReturn("{\"answer\":\"猜测\",\"segmentIds\":[999]}");

        assertThatThrownBy(() -> service.answer("问题", segments, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid transcript citation");
    }

    @Test
    void returnsTheCanonicalNoAnswerWhenTheModelHasNoCitation() {
        when(chatClient.chat(anyString(), anyList()))
                .thenReturn("{\"answer\":\"随便说点什么\",\"segmentIds\":[]}");

        VideoQuestionResponse response = service.answer("字幕中没有的问题", segments, List.of());

        assertThat(response.answer()).isEqualTo("视频字幕中没有足够信息回答这个问题。");
        assertThat(response.citations()).isEmpty();
    }
}

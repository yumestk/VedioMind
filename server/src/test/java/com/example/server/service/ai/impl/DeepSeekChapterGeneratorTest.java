package com.example.server.service.ai.impl;

import com.example.server.entity.TranscriptSegment;
import com.example.server.service.ai.VideoChapterDraft;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekChapterGeneratorTest {

    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final DeepSeekChapterGenerator generator = new DeepSeekChapterGenerator(chatClient);
    private final List<TranscriptSegment> segments = List.of(
            segment(11L, 0, 1_000, 4_000, "开场介绍"),
            segment(12L, 1, 4_000, 7_000, "继续说明背景"),
            segment(13L, 2, 7_000, 10_000, "进入实现细节")
    );

    @Test
    void derivesChapterTimesFromValidatedSegmentAnchors() {
        when(chatClient.chat(anyString(), anyString())).thenReturn("""
                ```json
                {"chapters":[
                  {"title":"背景介绍","startSegmentId":11},
                  {"title":"实现细节","startSegmentId":13}
                ]}
                ```
                """);

        List<VideoChapterDraft> chapters = generator.generate(segments);

        assertThat(chapters).containsExactly(
                new VideoChapterDraft("背景介绍", 1_000, 7_000),
                new VideoChapterDraft("实现细节", 7_000, 10_000)
        );
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatClient).chat(anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains(
                "\"segmentId\":11",
                "\"startMs\":1000",
                "\"text\":\"开场介绍\""
        );
    }

    @Test
    void rejectsChapterAnchorsThatAreNotInTheTranscript() {
        when(chatClient.chat(anyString(), anyString())).thenReturn("""
                {"chapters":[{"title":"虚构章节","startSegmentId":999}]}
                """);

        assertThatThrownBy(() -> generator.generate(segments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chapter segment order");
    }

    @Test
    void requiresTheFirstChapterToCoverTheStartOfTheTranscript() {
        when(chatClient.chat(anyString(), anyString())).thenReturn("""
                {"chapters":[{"title":"跳过开场","startSegmentId":12}]}
                """);

        assertThatThrownBy(() -> generator.generate(segments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chapter segment order");
    }

    private TranscriptSegment segment(Long id, int index, long startMs, long endMs, String text) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(id);
        segment.setMediaId(42L);
        segment.setSegmentIndex(index);
        segment.setStartMs(startMs);
        segment.setEndMs(endMs);
        segment.setText(text);
        return segment;
    }
}

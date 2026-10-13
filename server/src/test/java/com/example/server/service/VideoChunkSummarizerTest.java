package com.example.server.service;

import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoChunkSummarizerTest {

    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final VideoChunkSummarizer summarizer = new VideoChunkSummarizer(chatClient);

    @Test
    void returnsStructuredSummaryAndLabelsTranscriptAndOcrEvidence() {
        when(chatClient.chat(anyString(), any())).thenReturn(
                "<think>ignored</think>{\"summary\":\"核心内容\",\"keywords\":[\"索引\",\"检索\",\"长视频\"]}"
        );
        VideoChunkSummarizer.ChunkInput input = new VideoChunkSummarizer.ChunkInput(
                0,
                0,
                300_000,
                List.of(new VideoContext.TranscriptEvidence(1L, 1_000, 2_000, "字幕内容")),
                List.of(new VideoContext.VisualEvidence(
                        2L, 3_000, "frame.jpg", "SUCCEEDED", 90.0, "PPT 内容"
                ))
        );

        VideoChunkSummarizer.ChunkSummary result = summarizer.summarize(input);

        assertThat(result.summary()).isEqualTo("核心内容");
        assertThat(result.keywords()).containsExactly("索引", "检索", "长视频");
        ArgumentCaptor<List> messages = ArgumentCaptor.forClass(List.class);
        verify(chatClient).chat(anyString(), messages.capture());
        assertThat(messages.getValue().get(0).toString()).contains("字幕内容", "PPT 内容");
    }

    @Test
    void rejectsMalformedModelOutputInsteadOfCreatingAFallbackSummary() {
        when(chatClient.chat(anyString(), any())).thenReturn("not json");
        VideoChunkSummarizer.ChunkInput input = new VideoChunkSummarizer.ChunkInput(
                0,
                0,
                300_000,
                List.of(new VideoContext.TranscriptEvidence(1L, 0, 1_000, "text")),
                List.of()
        );

        assertThatThrownBy(() -> summarizer.summarize(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid JSON");
    }
}

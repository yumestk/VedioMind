package com.example.server.service.ai.impl;

import com.example.server.service.ai.TranscriptionResult;
import com.example.server.service.ai.AiRetryExecutor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AliyunSpeechTranscriberTest {

    private final AliyunSpeechTranscriber transcriber = new AliyunSpeechTranscriber(
            "test-key", mock(AiRetryExecutor.class)
    );

    @Test
    void parsesAndSortsCompletedTimestampedSentences() {
        String response = """
                {
                  "sentences": [
                    {"begin_time": 2100, "end_time": 3900, "text": "第二句"},
                    {"begin_time": 0, "end_time": 2000, "text": "第一句"},
                    {"begin_time": 4000, "text": "尚未完成"}
                  ]
                }
                """;

        TranscriptionResult result = transcriber.parseResponse(response);

        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().getFirst().text()).isEqualTo("第一句");
        assertThat(result.segments().getLast().startMs()).isEqualTo(2100);
        assertThat(result.fullText()).isEqualTo("第一句\n第二句");
    }

    @Test
    void rejectsAResponseWithoutCompletedSentences() {
        assertThatThrownBy(() -> transcriber.parseResponse("{\"sentences\": []}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no sentences");
    }
}

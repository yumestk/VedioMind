package com.example.server.consumer;

import com.example.server.service.AiService;
import com.example.server.service.AnalysisJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VideoAnalysisConsumerTest {

    private final AiService aiService = mock(AiService.class);
    private final AnalysisJobService analysisJobService = mock(AnalysisJobService.class);
    private final VideoAnalysisConsumer consumer = new VideoAnalysisConsumer(
            aiService,
            analysisJobService,
            new ObjectMapper()
    );

    @Test
    void acknowledgesOnlyAfterTheJobCompletes() {
        consumer.onMessage(message(0));

        verify(aiService).processAnalysisJob("job-1", 0);
        verify(analysisJobService, never()).markRetrying("job-1", 1, "provider unavailable");
    }

    @Test
    void recordsRetryStateAndPropagatesTheFailure() {
        doThrow(new IllegalStateException("provider unavailable"))
                .when(aiService).processAnalysisJob("job-1", 0);

        assertThatThrownBy(() -> consumer.onMessage(message(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");

        verify(analysisJobService).markRetrying("job-1", 1, "provider unavailable");
        verify(analysisJobService, never()).markFailed("job-1", 0, "provider unavailable");
    }

    @Test
    void recordsADeadLetterFailureAfterTheRetryLimit() {
        doThrow(new IllegalStateException("provider unavailable"))
                .when(aiService).processAnalysisJob("job-1", VideoAnalysisConsumer.MAX_RECONSUME_TIMES);

        assertThatThrownBy(() -> consumer.onMessage(message(VideoAnalysisConsumer.MAX_RECONSUME_TIMES)))
                .isInstanceOf(IllegalStateException.class);

        verify(analysisJobService).markFailed(
                "job-1",
                VideoAnalysisConsumer.MAX_RECONSUME_TIMES,
                "provider unavailable"
        );
        verify(analysisJobService, never()).markRetrying(
                "job-1",
                VideoAnalysisConsumer.MAX_RECONSUME_TIMES + 1,
                "provider unavailable"
        );
    }

    private MessageExt message(int reconsumeTimes) {
        MessageExt message = new MessageExt();
        message.setBody("{\"jobId\":\"job-1\",\"mediaId\":42}".getBytes(StandardCharsets.UTF_8));
        message.setReconsumeTimes(reconsumeTimes);
        return message;
    }
}

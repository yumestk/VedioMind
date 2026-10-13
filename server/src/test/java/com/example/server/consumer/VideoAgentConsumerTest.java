package com.example.server.consumer;

import com.example.server.service.VideoAgentRunService;
import com.example.server.service.VideoAgentWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VideoAgentConsumerTest {

    private final VideoAgentWorkflowService workflowService = mock(VideoAgentWorkflowService.class);
    private final VideoAgentRunService runService = mock(VideoAgentRunService.class);
    private final VideoAgentConsumer consumer = new VideoAgentConsumer(
            workflowService, runService, new ObjectMapper()
    );

    @Test
    void recordsRetryAndPropagatesWorkflowFailure() {
        doThrow(new IllegalStateException("model unavailable"))
                .when(workflowService).process("run-1", 0);

        assertThatThrownBy(() -> consumer.onMessage(message(0)))
                .isInstanceOf(IllegalStateException.class);

        verify(runService).markRetrying("run-1", 1, "model unavailable");
    }

    private MessageExt message(int retries) {
        MessageExt message = new MessageExt();
        message.setBody(("{\"runId\":\"run-1\",\"mediaId\":42,"
                + "\"goal\":\"learn\",\"contextVersion\":\"v1\"}")
                .getBytes(StandardCharsets.UTF_8));
        message.setReconsumeTimes(retries);
        return message;
    }
}

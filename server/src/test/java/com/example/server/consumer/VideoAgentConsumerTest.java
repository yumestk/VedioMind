package com.example.server.consumer;

import com.example.server.service.VideoAgentWorkflowService;
import com.example.server.service.BackgroundTaskRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;

import static com.example.server.service.BackgroundTaskRecoveryService.TaskType.AGENT;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoAgentConsumerTest {
    private final VideoAgentWorkflowService service = mock(VideoAgentWorkflowService.class);
    private final BackgroundTaskRecoveryService recovery = mock(BackgroundTaskRecoveryService.class);
    private final VideoAgentConsumer consumer = new VideoAgentConsumer(service, recovery, new ObjectMapper());

    @Test
    void delegatesWithDatabaseFailureCountInsteadOfBrokerRedeliveryCount() {
        doAnswer(call -> {
            call.getArgument(3, IntConsumer.class).accept(2);
            return null;
        }).when(recovery).execute(eq(AGENT), eq("task-1"), eq(0L), any());
        MessageExt message = message();
        message.setReconsumeTimes(99);

        consumer.onMessage(message);

        verify(service).process("task-1", 2);
    }

    @Test
    void passesRecoveryVersionToRejectStaleDeliveries() {
        MessageExt message = message();
        message.putUserProperty(BackgroundTaskRecoveryService.VERSION_HEADER, "7");

        consumer.onMessage(message);

        verify(recovery).execute(eq(AGENT), eq("task-1"), eq(7L), any());
    }

    private MessageExt message() {
        MessageExt message = new MessageExt();
        message.setBody("{\"runId\":\"task-1\",\"mediaId\":42}".getBytes(StandardCharsets.UTF_8));
        return message;
    }
}

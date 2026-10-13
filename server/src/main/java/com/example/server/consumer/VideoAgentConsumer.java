package com.example.server.consumer;

import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.service.VideoAgentRunService;
import com.example.server.service.VideoAgentWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RocketMQMessageListener(
        topic = "video-agent-topic",
        consumerGroup = "video-agent-consumer-group",
        consumeThreadNumber = 1,
        consumeThreadMax = 2,
        consumeTimeout = 15,
        maxReconsumeTimes = VideoAgentConsumer.MAX_RECONSUME_TIMES
)
public class VideoAgentConsumer implements RocketMQListener<MessageExt> {

    static final int MAX_RECONSUME_TIMES = 3;

    private final VideoAgentWorkflowService workflowService;
    private final VideoAgentRunService runService;
    private final ObjectMapper objectMapper;

    public VideoAgentConsumer(
            VideoAgentWorkflowService workflowService,
            VideoAgentRunService runService,
            ObjectMapper objectMapper
    ) {
        this.workflowService = workflowService;
        this.runService = runService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(MessageExt message) {
        VideoAgentRunMessage command = readMessage(message);
        int retryCount = message.getReconsumeTimes();
        try {
            workflowService.process(command.runId(), retryCount);
        } catch (RuntimeException exception) {
            String error = concise(exception);
            if (retryCount >= MAX_RECONSUME_TIMES) {
                runService.markFailed(command.runId(), retryCount, error);
            } else {
                runService.markRetrying(command.runId(), retryCount + 1, error);
            }
            throw exception;
        }
    }

    private VideoAgentRunMessage readMessage(MessageExt message) {
        try {
            return objectMapper.readValue(message.getBody(), VideoAgentRunMessage.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Invalid video Agent message: "
                            + new String(message.getBody(), StandardCharsets.UTF_8),
                    exception
            );
        }
    }

    private String concise(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}

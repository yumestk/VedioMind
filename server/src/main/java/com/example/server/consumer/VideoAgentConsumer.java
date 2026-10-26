package com.example.server.consumer;

import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.service.VideoAgentWorkflowService;
import com.example.server.service.BackgroundTaskRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import static com.example.server.service.BackgroundTaskRecoveryService.TaskType.AGENT;

@Component
@RocketMQMessageListener(
        topic = "video-agent-topic",
        consumerGroup = "video-agent-consumer-group",
        consumeThreadNumber = 1,
        consumeThreadMax = 2,
        consumeTimeout = 15,
        maxReconsumeTimes = 3
)
public class VideoAgentConsumer implements RocketMQListener<MessageExt> {
    private final VideoAgentWorkflowService service;
    private final BackgroundTaskRecoveryService recovery;
    private final ObjectMapper objectMapper;

    public VideoAgentConsumer(VideoAgentWorkflowService service, BackgroundTaskRecoveryService recovery, ObjectMapper objectMapper) {
        this.service = service;
        this.recovery = recovery;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(MessageExt message) {
        VideoAgentRunMessage command;
        long version;
        try {
            command = objectMapper.readValue(message.getBody(), VideoAgentRunMessage.class);
            String header = message.getProperty(BackgroundTaskRecoveryService.VERSION_HEADER);
            version = header == null ? 0 : Long.parseLong(header);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid background task message", exception);
        }
        recovery.execute(AGENT, command.runId(), version,
                retries -> service.process(command.runId(), retries));
    }
}

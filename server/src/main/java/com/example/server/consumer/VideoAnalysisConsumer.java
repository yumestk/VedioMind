package com.example.server.consumer;

import com.example.server.dto.AnalysisJobMessage;
import com.example.server.service.AiService;
import com.example.server.service.BackgroundTaskRecoveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import static com.example.server.service.BackgroundTaskRecoveryService.TaskType.ANALYSIS;

@Component
@RocketMQMessageListener(
        topic = "video-analysis-topic",
        consumerGroup = "video-analysis-consumer-group",
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        consumeTimeout = 120,
        maxReconsumeTimes = 3
)
public class VideoAnalysisConsumer implements RocketMQListener<MessageExt> {
    private final AiService service;
    private final BackgroundTaskRecoveryService recovery;
    private final ObjectMapper objectMapper;

    public VideoAnalysisConsumer(AiService service, BackgroundTaskRecoveryService recovery, ObjectMapper objectMapper) {
        this.service = service;
        this.recovery = recovery;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(MessageExt message) {
        AnalysisJobMessage command;
        long version;
        try {
            command = objectMapper.readValue(message.getBody(), AnalysisJobMessage.class);
            String header = message.getProperty(BackgroundTaskRecoveryService.VERSION_HEADER);
            version = header == null ? 0 : Long.parseLong(header);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid background task message", exception);
        }
        recovery.execute(ANALYSIS, command.jobId(), version,
                retries -> service.processAnalysisJob(command.jobId(), retries));
    }
}

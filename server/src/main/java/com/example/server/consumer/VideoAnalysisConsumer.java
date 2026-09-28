package com.example.server.consumer;

import com.example.server.dto.AnalysisJobMessage;
import com.example.server.service.AiService;
import com.example.server.service.AnalysisJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RocketMQMessageListener(
        topic = "video-analysis-topic",
        consumerGroup = "video-analysis-consumer-group",
        consumeThreadNumber = 2,
        consumeThreadMax = 4,
        consumeTimeout = 30,
        maxReconsumeTimes = VideoAnalysisConsumer.MAX_RECONSUME_TIMES
)
public class VideoAnalysisConsumer implements RocketMQListener<MessageExt> {

    static final int MAX_RECONSUME_TIMES = 3;

    private final AiService aiService;
    private final AnalysisJobService analysisJobService;
    private final ObjectMapper objectMapper;

    public VideoAnalysisConsumer(
            AiService aiService,
            AnalysisJobService analysisJobService,
            ObjectMapper objectMapper
    ) {
        this.aiService = aiService;
        this.analysisJobService = analysisJobService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(MessageExt message) {
        AnalysisJobMessage command = readMessage(message);
        int retryCount = message.getReconsumeTimes();

        try {
            aiService.processAnalysisJob(command.jobId(), retryCount);
        } catch (RuntimeException exception) {
            String errorMessage = conciseMessage(exception);
            if (retryCount >= MAX_RECONSUME_TIMES) {
                analysisJobService.markFailed(command.jobId(), retryCount, errorMessage);
            } else {
                analysisJobService.markRetrying(command.jobId(), retryCount + 1, errorMessage);
            }
            throw exception;
        }
    }

    private AnalysisJobMessage readMessage(MessageExt message) {
        try {
            return objectMapper.readValue(message.getBody(), AnalysisJobMessage.class);
        } catch (Exception exception) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            throw new IllegalArgumentException("Invalid analysis message: " + body, exception);
        }
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}

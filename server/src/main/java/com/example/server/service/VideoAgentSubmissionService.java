package com.example.server.service;

import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.AiRateLimitService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class VideoAgentSubmissionService {

    public static final String TOPIC = "video-agent-topic";
    public static final String TASK_TYPE_HEADER = "task_type";
    public static final String TASK_TYPE = "VIDEO_AGENT";

    private final RocketMQTemplate rocketMQTemplate;
    private final MediaService mediaService;
    private final VideoKnowledgeIndexService indexService;
    private final VideoAgentRunService runService;
    private final AiRateLimitService rateLimitService;

    public VideoAgentSubmissionService(
            RocketMQTemplate rocketMQTemplate,
            MediaService mediaService,
            VideoKnowledgeIndexService indexService,
            VideoAgentRunService runService,
            AiRateLimitService rateLimitService
    ) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.mediaService = mediaService;
        this.indexService = indexService;
        this.runService = runService;
        this.rateLimitService = rateLimitService;
    }

    public VideoAgentRun submit(Long mediaId, Long userId, String goal) {
        String normalizedGoal = normalizeGoal(goal);
        mediaService.requireOwnership(mediaId, userId);
        if (!mediaService.isStudyMode(mediaId, userId)) {
            throw new IllegalArgumentException("Learning Agent is only available in Study Mode");
        }
        String contextVersion = indexService.requireReady(mediaId).context().version();
        rateLimitService.requireRequest(userId);
        VideoAgentRunMessage command = new VideoAgentRunMessage(
                UUID.randomUUID().toString(), mediaId, normalizedGoal, contextVersion
        );
        TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(
                TOPIC,
                MessageBuilder.withPayload(command)
                        .setHeader(TASK_TYPE_HEADER, TASK_TYPE)
                        .build(),
                command
        );
        if (result.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
            throw new IllegalStateException("The learning Agent transaction was rolled back");
        }
        return runService.findById(command.runId())
                .orElseThrow(() -> new IllegalStateException("The learning Agent transaction is not confirmed yet"));
    }

    private String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("Learning goal must not be empty");
        }
        String normalized = goal.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("Learning goal must not exceed 500 characters");
        }
        return normalized;
    }
}

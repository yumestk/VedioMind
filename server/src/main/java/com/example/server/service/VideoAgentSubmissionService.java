package com.example.server.service;

import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.entity.VideoAgentRun;
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
    private final VideoConversationStore conversations;
    private final StudyTaskPreparationService tasks;

    public VideoAgentSubmissionService(
            RocketMQTemplate rocketMQTemplate,
            MediaService mediaService,
            VideoKnowledgeIndexService indexService,
            VideoAgentRunService runService,
            VideoConversationStore conversations, StudyTaskPreparationService tasks
    ) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.mediaService = mediaService;
        this.indexService = indexService;
        this.runService = runService;
        this.conversations = conversations; this.tasks = tasks;
    }

    public VideoAgentRun submit(com.example.server.dto.PreparedStudyTask task) {
        Long mediaId = task.anchorMediaId(), userId = task.learnerId(), conversationId = task.conversationId();
        String normalizedGoal = normalizeGoal(task.goal());
        var conversation = conversations.getRequired(conversationId);
        if (!userId.equals(conversation.getLearnerId()) || !mediaId.equals(conversation.getMediaId()))
            throw new java.util.NoSuchElementException("Study conversation not found");
        mediaService.requireReadable(mediaId, conversation.getCourseId(), userId);
        tasks.assertCurrent(task);
        if (!mediaService.isStudyMode(mediaId, userId)) {
            throw new IllegalArgumentException("Learning Agent is only available in Study Mode");
        }
        String contextVersion = indexService.requireReady(mediaId).context().version();
        if (!contextVersion.equals(task.materials().getFirst().version()))
            throw new StudyTaskPreparationService.SourceChangedException("材料版本已变更，请重新提交任务");
        VideoAgentRunMessage command = new VideoAgentRunMessage(
                UUID.randomUUID().toString(), mediaId, conversationId, userId, conversation.getCourseId(),
                normalizedGoal, contextVersion, task
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

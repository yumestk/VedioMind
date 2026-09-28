package com.example.server.service;

import com.example.server.dto.AnalysisJobMessage;
import com.example.server.entity.AnalysisJob;
import com.example.server.mapper.MediaFileMapper;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AnalysisSubmissionService {

    public static final String TOPIC = "video-analysis-topic";
    public static final String JOB_ID_HEADER = "analysis_job_id";

    private final RocketMQTemplate rocketMQTemplate;
    private final AnalysisJobService analysisJobService;
    private final MediaFileMapper mediaFileMapper;
    private final RedissonClient redissonClient;

    public AnalysisSubmissionService(
            RocketMQTemplate rocketMQTemplate,
            AnalysisJobService analysisJobService,
            MediaFileMapper mediaFileMapper,
            RedissonClient redissonClient
    ) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.analysisJobService = analysisJobService;
        this.mediaFileMapper = mediaFileMapper;
        this.redissonClient = redissonClient;
    }

    public AnalysisJob submit(Long mediaId) {
        if (mediaFileMapper.selectById(mediaId) == null) {
            throw new IllegalArgumentException("Media does not exist: " + mediaId);
        }

        RLock lock = redissonClient.getLock("lock:submit-analysis:" + mediaId);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("Another analysis submission is in progress");
            }

            AnalysisJob activeJob = analysisJobService.findLatestActive(mediaId).orElse(null);
            if (activeJob != null) {
                return activeJob;
            }

            AnalysisJobMessage command = new AnalysisJobMessage(UUID.randomUUID().toString(), mediaId);
            TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(
                    TOPIC,
                    MessageBuilder.withPayload(command)
                            .setHeader(JOB_ID_HEADER, command.jobId())
                            .build(),
                    command
            );

            if (result.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
                throw new IllegalStateException("The analysis job transaction was rolled back");
            }

            return analysisJobService.findById(command.jobId())
                    .orElseThrow(() -> new IllegalStateException("The analysis job transaction is not confirmed yet"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while submitting the analysis job", exception);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

package com.example.server.producer;

import com.example.server.dto.AnalysisJobMessage;
import com.example.server.service.AnalysisJobService;
import com.example.server.service.AnalysisSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

@RocketMQTransactionListener
public class AnalysisTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTransactionListener.class);

    private final AnalysisJobService analysisJobService;
    private final ObjectMapper objectMapper;

    public AnalysisTransactionListener(AnalysisJobService analysisJobService, ObjectMapper objectMapper) {
        this.analysisJobService = analysisJobService;
        this.objectMapper = objectMapper;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object argument) {
        try {
            AnalysisJobMessage command = (AnalysisJobMessage) argument;
            analysisJobService.createQueuedJob(command);
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception exception) {
            log.error("Failed to create the local analysis job transaction", exception);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        try {
            AnalysisJobMessage command = readMessage(message);
            return analysisJobService.findById(command.jobId()).isPresent()
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception exception) {
            log.error("Failed to check the local analysis job transaction", exception);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private AnalysisJobMessage readMessage(Message message) throws Exception {
        Object payload = message.getPayload();
        if (payload instanceof byte[] bytes) {
            return objectMapper.readValue(bytes, AnalysisJobMessage.class);
        }
        if (payload instanceof AnalysisJobMessage command) {
            return command;
        }
        return objectMapper.readValue(String.valueOf(payload), AnalysisJobMessage.class);
    }
}

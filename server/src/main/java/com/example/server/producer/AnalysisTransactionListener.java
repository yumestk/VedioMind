package com.example.server.producer;

import com.example.server.dto.AnalysisJobMessage;
import com.example.server.dto.VideoAgentRunMessage;
import com.example.server.service.AnalysisJobService;
import com.example.server.service.VideoAgentRunService;
import com.example.server.service.VideoAgentSubmissionService;
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
    private final VideoAgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public AnalysisTransactionListener(
            AnalysisJobService analysisJobService,
            VideoAgentRunService agentRunService,
            ObjectMapper objectMapper
    ) {
        this.analysisJobService = analysisJobService;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object argument) {
        try {
            if (argument instanceof VideoAgentRunMessage command) {
                agentRunService.createQueuedRun(command);
            } else {
                analysisJobService.createQueuedJob((AnalysisJobMessage) argument);
            }
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception exception) {
            log.error("Failed to create the local task transaction", exception);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        try {
            boolean exists;
            if (VideoAgentSubmissionService.TASK_TYPE.equals(message.getHeaders().get(
                    VideoAgentSubmissionService.TASK_TYPE_HEADER, String.class))) {
                VideoAgentRunMessage command = readAgentMessage(message);
                exists = agentRunService.findById(command.runId()).isPresent();
            } else {
                AnalysisJobMessage command = readMessage(message);
                exists = analysisJobService.findById(command.jobId()).isPresent();
            }
            return exists
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception exception) {
            log.error("Failed to check the local task transaction", exception);
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

    private VideoAgentRunMessage readAgentMessage(Message message) throws Exception {
        Object payload = message.getPayload();
        if (payload instanceof byte[] bytes) {
            return objectMapper.readValue(bytes, VideoAgentRunMessage.class);
        }
        if (payload instanceof VideoAgentRunMessage command) {
            return command;
        }
        return objectMapper.readValue(String.valueOf(payload), VideoAgentRunMessage.class);
    }
}

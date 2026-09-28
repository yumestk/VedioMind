package com.example.server.producer;

import com.example.server.dto.AnalysisJobMessage;
import com.example.server.service.AnalysisJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisTransactionListenerTest {

    private final AnalysisJobService analysisJobService = mock(AnalysisJobService.class);
    private final AnalysisTransactionListener listener = new AnalysisTransactionListener(
            analysisJobService,
            new ObjectMapper()
    );

    @Test
    void commitsWhenTheJobIsCreated() {
        AnalysisJobMessage command = new AnalysisJobMessage("job-1", 10L);
        Message<AnalysisJobMessage> message = MessageBuilder.withPayload(command).build();

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(message, command);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
        verify(analysisJobService).createQueuedJob(command);
    }

    @Test
    void rollsBackWhenTheJobCannotBeCreated() {
        AnalysisJobMessage command = new AnalysisJobMessage("job-2", 11L);
        Message<AnalysisJobMessage> message = MessageBuilder.withPayload(command).build();
        doThrow(new IllegalStateException("database unavailable"))
                .when(analysisJobService).createQueuedJob(command);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(message, command);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void transactionCheckReadsThePersistedJob() throws Exception {
        AnalysisJobMessage command = new AnalysisJobMessage("job-3", 12L);
        Message<byte[]> message = MessageBuilder.withPayload(
                new ObjectMapper().writeValueAsBytes(command)
        ).build();
        when(analysisJobService.findById("job-3"))
                .thenReturn(java.util.Optional.of(new com.example.server.entity.AnalysisJob()));

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(message);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }
}

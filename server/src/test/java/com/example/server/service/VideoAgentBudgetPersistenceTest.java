package com.example.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.example.server.entity.VideoAgentRun;
import com.example.server.mapper.*;
import com.example.server.dto.VideoAgentResult;
import com.example.server.service.ai.AiPromptBudget;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoAgentBudgetPersistenceTest {
    @Test void persistedDeadlineCountersAndPartialResultSurviveRedelivery() throws Exception {
        JdbcDataSource db=new JdbcDataSource();
        db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE video_agent_runs (id VARCHAR(36) PRIMARY KEY,media_id BIGINT,goal VARCHAR(500),context_version VARCHAR(100),status VARCHAR(24),progress INT,retry_count INT,dispatch_version BIGINT,next_attempt_at TIMESTAMP,quota_wait_started_at TIMESTAMP,generation_calls INT,retrieval_calls INT,input_tokens INT,output_tokens INT,token_usage_known BOOLEAN,result_json CLOB,citations_json CLOB,error_message VARCHAR(2000),created_at TIMESTAMP,updated_at TIMESTAMP,started_at TIMESTAMP,deadline_at TIMESTAMP,finished_at TIMESTAMP)");
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test",new JdbcTransactionFactory(),db));
        config.addMapper(VideoAgentRunMapper.class);
        var factory=new MybatisSqlSessionFactoryBuilder().build(config);
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(VideoAgentRunMapper.class);
            var service=new VideoAgentRunService(mapper,mock(VideoAgentStepMapper.class),mock(MediaFileMapper.class),mock(VideoConversationStore.class));
            var run=new VideoAgentRun();run.setId("r");run.setStatus("QUEUED");run.setGenerationCalls(0);run.setRetrievalCalls(0);run.setInputTokens(0);run.setOutputTokens(0);run.setTokenUsageKnown(true);
            mapper.insert(run);
            assertThat(service.beginAttempt("r",0)).isTrue();
            run=service.getRequired("r");
            assertThat(java.time.Duration.between(run.getStartedAt(),run.getDeadlineAt()).getSeconds()).isEqualTo(120);
            var firstDeadline=run.getDeadlineAt();
            service.reserveGenerationCall("r"); service.reserveGenerationCall("r");service.reserveGenerationCall("r");
            assertThatThrownBy(() -> service.reserveGenerationCall("r")).isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
            for(int i=0;i<6;i++) service.reserveRetrievalCall("r");
            assertThatThrownBy(() -> service.reserveRetrievalCall("r")).isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
            service.beginAttempt("r",1);
            assertThat(service.getRequired("r").getGenerationCalls()).isEqualTo(3);
            assertThat(service.getRequired("r").getDeadlineAt()).isEqualTo(firstDeadline);
            service.addTokens("r",12,8192);service.addTokens("r",null,null);
            assertThat(service.getRequired("r").getTokenUsageKnown()).isFalse();
            assertThat(service.getRequired("r").getOutputTokens()).isEqualTo(8192);
            service.saveUsableResult("r",new VideoAgentResult("t","c",List.of("TRANSCRIPT:1"),List.of(new VideoAgentResult.KnowledgePoint("p","e",List.of("TRANSCRIPT:1"))),List.of(),List.of("gap")),List.of());
            jdbc.update("UPDATE video_agent_runs SET deadline_at=? WHERE id='r'",LocalDateTime.now().minusSeconds(1));
            service.finishStopped("r","deadline");
            assertThat(service.getRequired("r").getStatus()).isEqualTo("PARTIAL");
            assertThat(service.getRequired("r").getResultJson()).contains("gap");
            assertThat(service.beginAttempt("r",2)).isFalse();
            assertThat(service.findLatestActive(34L)).isEmpty();
        }
        // Concurrent consumers cannot reserve more than the shared HTTP request budget.
        jdbc.update("UPDATE video_agent_runs SET status='DRAFTING',generation_calls=0,deadline_at=? WHERE id='r'",LocalDateTime.now().plusSeconds(120));
        AtomicInteger allowed=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(10)) {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<20;i++) tasks.add(pool.submit(() -> {
                try(var session=factory.openSession(true)) {
                    var service=new VideoAgentRunService(session.getMapper(VideoAgentRunMapper.class),mock(VideoAgentStepMapper.class),mock(MediaFileMapper.class),mock(VideoConversationStore.class));
                    try { service.reserveGenerationCall("r");allowed.incrementAndGet(); }
                    catch(AiPromptBudget.PromptBudgetExceededException expected) { }
                }
            }));
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
        }
        assertThat(allowed).hasValue(3);
    }
}

package com.example.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.example.server.dto.*;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.external.ExternalVideoImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL constraints, MyBatis, services and Spring transactions; all external/paid work is mocked. */
@SpringJUnitConfig(StudyAuthorizationMySqlTest.Config.class)
@EnabledIfEnvironmentVariable(named="STUDY_TEST_MYSQL_URL", matches=".+")
@Transactional
class StudyAuthorizationMySqlTest {
    @Configuration @EnableTransactionManagement
    @Import({StudyAccessService.class, CourseService.class, MediaService.class, VideoConversationStore.class,
            VideoConversationService.class, VideoAgentRunService.class, VideoAgentSubmissionService.class,
            AssistantDispatchService.class, AssistantModeRouter.class, StudyTaskPreparationService.class, StudyEvidenceService.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url=System.getenv("STUDY_TEST_MYSQL_URL");
            if (!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?"))
                throw new IllegalArgumentException("Isolated study database required");
            var db=new DriverManagerDataSource(url,System.getenv("AUTH_TEST_MYSQL_USER"),System.getenv("AUTH_TEST_MYSQL_PASSWORD"));
            var flyway=Flyway.configure().dataSource(db).load(); flyway.migrate(); flyway.validate();
            return db;
        }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean org.springframework.transaction.PlatformTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory factory(DataSource ds) {
            var c=new MybatisConfiguration(); c.setMapUnderscoreToCamelCase(true);
            c.setEnvironment(new Environment("study-test",new SpringManagedTransactionFactory(),ds));
            for (var type:List.of(MediaFileMapper.class,VideoConversationMapper.class,VideoMessageMapper.class,
                    VideoMessageCitationMapper.class,VideoAgentRunMapper.class,VideoAgentStepMapper.class,MediaVisualEvidenceMapper.class,VideoKeyframeMapper.class)) c.addMapper(type);
            return new MybatisSqlSessionFactoryBuilder().build(c);
        }
        @Bean SqlSessionTemplate sql(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean MediaVisualEvidenceMapper manifestMapper(SqlSessionTemplate sql) { return sql.getMapper(MediaVisualEvidenceMapper.class); }
        @Bean VideoKeyframeMapper keyframeMapper(SqlSessionTemplate sql) { return sql.getMapper(VideoKeyframeMapper.class); }
        @Bean MediaFileMapper mediaMapper(SqlSessionTemplate sql) { return sql.getMapper(MediaFileMapper.class); }
        @Bean VideoConversationMapper conversationMapper(SqlSessionTemplate sql) { return sql.getMapper(VideoConversationMapper.class); }
        @Bean VideoMessageMapper messageMapper(SqlSessionTemplate sql) { return sql.getMapper(VideoMessageMapper.class); }
        @Bean VideoMessageCitationMapper citationMapper(SqlSessionTemplate sql) { return sql.getMapper(VideoMessageCitationMapper.class); }
        @Bean VideoAgentRunMapper runMapper(SqlSessionTemplate sql) { return sql.getMapper(VideoAgentRunMapper.class); }
        @Bean VideoAgentStepMapper stepMapper(SqlSessionTemplate sql) { return sql.getMapper(VideoAgentStepMapper.class); }
        @Bean ObjectStorageService storage() { return mock(ObjectStorageService.class); }
        @Bean ExternalVideoImportService imports() { return mock(ExternalVideoImportService.class); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TranscriptService transcript() { return mock(TranscriptService.class); }
        @Bean ChapterService chapters() { return mock(ChapterService.class); }
        @Bean VisualEvidenceService visual() { return mock(VisualEvidenceService.class); }
        @Bean QdrantKnowledgeIndexService qdrant() { return mock(QdrantKnowledgeIndexService.class); }
        @Bean RedissonClient locks() throws Exception {
            var client=mock(RedissonClient.class); var lock=mock(RLock.class);
            when(client.getLock(anyString())).thenReturn(lock); when(lock.tryLock(0,TimeUnit.SECONDS)).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true); return client;
        }
        @Bean com.example.server.service.context.VideoContextService contexts() { return mock(com.example.server.service.context.VideoContextService.class); }
        @Bean com.example.server.service.ai.AiPromptBudget budget() { return new com.example.server.service.ai.AiPromptBudget(64000,8192); }
        @Bean VideoQuestionService questions() { return mock(VideoQuestionService.class); }
        @Bean VideoKnowledgeRetriever retrieval() { return mock(VideoKnowledgeRetriever.class); }
        @Bean VideoEvidenceContextService evidence() { return new VideoEvidenceContextService(3,30); }
        @Bean KnowledgeIndexPreparationService preparation() { return mock(KnowledgeIndexPreparationService.class); }
        @Bean VideoKnowledgeIndexService index() { return mock(VideoKnowledgeIndexService.class); }
        @Bean RocketMQTemplate messages() { return mock(RocketMQTemplate.class); }
        @Bean AiRateLimitService quota() { return mock(AiRateLimitService.class); }
        @Bean DeepSeekChatClient model() { return mock(DeepSeekChatClient.class); }
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseService courses;
    @Autowired StudyAccessService access;
    @Autowired MediaService media;
    @Autowired VideoConversationService conversations;
    @Autowired VideoConversationStore store;
    @Autowired VideoAgentRunService runs;
    @Autowired VideoAgentSubmissionService submissions;
    @Autowired AssistantDispatchService dispatch;
    @Autowired ObjectStorageService storage;
    @Autowired RocketMQTemplate mq;
    @Autowired VideoQuestionService questions;
    @Autowired TranscriptService transcript;
    @Autowired VideoKnowledgeRetriever retrieval;
    @Autowired KnowledgeIndexPreparationService preparation;
    @Autowired AiRateLimitService quota;
    @Autowired VideoKnowledgeIndexService index;
    @Autowired DeepSeekChatClient model;
    @Autowired StudyTaskPreparationService tasks;
    @Autowired com.example.server.service.context.VideoContextService contexts;
    Long courseId, sectionId;

    @BeforeEach void sample() {
        reset(storage,mq,questions,transcript,retrieval,preparation,quota,index,model,contexts);
        when(contexts.currentVisualState(anyLong())).thenReturn("AVAILABLE");
        when(contexts.currentVersion(anyLong())).thenReturn("v");
        for (long id:List.of(1L,2L,3L)) jdbc.update("INSERT INTO users(id,username,password) VALUES (?,?,'test-hash')",id,"actor-"+id);
        jdbc.update("INSERT INTO media_files(id,user_id,filename,status,analysis_mode,object_key,content_sha256) VALUES (10,1,'Lecture 1','COMPLETED','STUDY','private/video','abc')");
        jdbc.update("INSERT INTO media_files(id,user_id,filename,status,analysis_mode) VALUES (11,1,'Private lecture','COMPLETED','STUDY'),(12,2,'B private','COMPLETED','STUDY')");
        var course=courses.create(1L,"Exam revision",""); courseId=course.course().id();
        var detail=courses.addSection(courseId,1L,"Chapter 3",0); sectionId=detail.sections().getFirst().id();
        courses.addMaterial(courseId,sectionId,10L,1L,0);
        when(storage.createReadUrl("private/video")).thenReturn("signed-for-readable-source");
    }

    @Test void publishedCourseIsSharedButPrivateLearningIsNot() {
        assertThat(courses.list(2L)).isEmpty();
        assertThatThrownBy(() -> media.getDetail(10L,2L)).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(storage);
        courses.publish(courseId,1L,true);
        assertThat(media.getDetail(10L,2L).canManage()).isFalse();
        assertThat(media.getDetail(10L,3L).playbackUrl()).isEqualTo("signed-for-readable-source");
        var b=conversations.create(10L,2L,courseId); var c=conversations.create(10L,3L,courseId);
        assertThat(conversations.list(10L,2L,courseId)).extracting(VideoConversationResponse::id).containsExactly(b.id());
        assertThat(conversations.list(10L,3L,courseId)).extracting(VideoConversationResponse::id).containsExactly(c.id());
        for (long other:List.of(1L,3L)) {
            assertThatThrownBy(() -> conversations.messages(b.id(),other)).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> conversations.get(10L,b.id(),other)).isInstanceOf(NoSuchElementException.class);
        }
        runs.createQueuedRun(command("b-run",b.id(),2L)); runs.createQueuedRun(command("c-run",c.id(),3L));
        assertThat(runs.findLatestActive(b.id(),2L)).isPresent();
        assertThat(runs.findLatestActive(b.id(),3L)).isEmpty();
        assertThatThrownBy(() -> runs.getOwned("b-run",1L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> runs.getOwned("b-run",3L)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> runs.createQueuedRun(command("b-again",b.id(),2L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs",Integer.class)).isEqualTo(2);
        // A database writer cannot bypass the per-conversation active constraint or forge learner identity.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO video_agent_runs(id,media_id,learner_id,conversation_id,course_id,task_scope,goal,context_version,status) "
                + "VALUES ('race',10,2,?,?,JSON_OBJECT(),'x','v','QUEUED')",b.id(),courseId)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO video_agent_runs(id,media_id,learner_id,conversation_id,task_scope,goal,context_version,status) "
                + "VALUES ('forged',10,3,?,JSON_OBJECT(),'x','v','SUCCEEDED')",b.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void rejectedRequestsHaveNoQuotaMqSigningOrGenerationSideEffects() {
        courses.publish(courseId,1L,true);
        var b=conversations.create(10L,2L,courseId);
        var c=conversations.create(10L,3L,courseId);
        assertThatThrownBy(() -> dispatch.dispatch(11L,2L,new AssistantDispatchRequest("解释", "QUICK", null, courseId, null))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> dispatch.dispatch(10L,2L,new AssistantDispatchRequest("解释", "QUICK", c.id(), courseId, null))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> dispatch.dispatch(10L,2L,new AssistantDispatchRequest("解释", "QUICK", b.id(), null, null))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> submissions.submit(StudyTaskFixtures.video(10L,3L,b.id(),courseId,"notes","v"))).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> courses.addMaterial(courseId,sectionId,12L,1L,1)).isInstanceOf(NoSuchElementException.class);
        var other=courses.create(1L,"Other","");
        assertThatThrownBy(() -> courses.addMaterial(other.course().id(),sectionId,10L,1L,0)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> courses.publish(courseId,2L,false)).isInstanceOf(NoSuchElementException.class);
        assertThat(access.canRead(11L,null,2L)).isFalse();
        verifyNoInteractions(quota,mq,storage,questions,model,index,preparation);
    }

    @Test void withdrawalAndDeletionPreserveResultsHideEvidenceAndStopAffectedRuns() {
        courses.publish(courseId,1L,true);
        var b=conversations.create(10L,2L,courseId);
        store.savePreparedTurn(store.getRequired(b.id()),tasks.prepare(10L,2L,b.id(),courseId,null,"question"),new VideoQuestionResponse("saved answer",List.of(new VideoQuestionCitationResponse(99L,100L,200L,"secret source text"))));
        runs.createQueuedRun(command("done",b.id(),2L));
        jdbc.update("UPDATE video_agent_runs SET status='SUCCEEDED',result_json=?,citations_json=? WHERE id='done'", resultJson(),citationsJson());
        runs.createQueuedRun(command("active",b.id(),2L));
        courses.publish(courseId,1L,false);
        assertThat(runs.getRequired("active").getStatus()).isEqualTo("FAILED");
        assertThat(runs.beginAttempt("active",0)).isFalse();
        assertThatThrownBy(() -> media.getDetail(10L,2L)).isInstanceOf(NoSuchElementException.class);
        var timeline=conversations.messages(b.id(),2L);
        assertThat(timeline.sourceAvailable()).isFalse();
        assertThat(timeline.messages()).allSatisfy(message -> {
            assertThat(message.citations()).allSatisfy(c -> { assertThat(c.sourceAvailable()).isFalse(); assertThat(c.text()).isNull(); });
            if(message.agentRun()!=null) assertThat(message.agentRun().citations()).isEmpty();
        });
        assertThat(timeline.messages()).anySatisfy(m -> assertThat(m.content()).isEqualTo("saved answer"));
        assertThat(VideoAgentRunResponse.from(runs.getOwned("done",2L),access.canRead(10L,courseId,2L)).result().title()).isEqualTo("Saved notes");
        assertThat(conversations.personal(2L)).singleElement().satisfies(c -> assertThat(c.sourceAvailable()).isFalse());
        // Re-publish restores source access; removal invalidates exactly that course context.
        courses.publish(courseId,1L,true);
        var material=courses.detail(courseId,1L).sections().getFirst().materials().getFirst();
        courses.removeMaterial(courseId,material.id(),1L);
        assertThat(conversations.messages(b.id(),2L).sourceAvailable()).isFalse();
        courses.addMaterial(courseId,sectionId,10L,1L,0);
        media.delete(10L,1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations WHERE id=?",Integer.class,b.id())).isEqualTo(1);
        assertThat(runs.getOwned("done",2L).getResultJson()).contains("Saved notes");
        assertThatThrownBy(() -> media.getTranscript(10L,1L)).isInstanceOf(NoSuchElementException.class);
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM media_files WHERE id=10",String.class)).isNull();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM media_files WHERE id=10")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void singleVideoQuickPathRemainsAvailableAndStudentCannotStartPaidPreparation() throws Exception {
        var segment=new TranscriptSegment(); segment.setId(99L); segment.setStartMs(100L); segment.setEndMs(200L);segment.setText("source");segment.setSegmentIndex(0);
        when(transcript.listByMediaId(10L)).thenReturn(List.of(segment));
        when(questions.fitsPrepared(any(),any(),any(),any())).thenReturn(true);
        when(questions.answerPrepared(any(), any(), any(), any(), eq(false))).thenReturn(new VideoQuestionResponse("answer",List.of()));
        var response=dispatch.dispatch(10L,1L,new AssistantDispatchRequest("解释", "QUICK", null, null, null));
        assertThat(response.timeline().messages()).hasSize(2);
        assertThat(store.getRequired(response.conversation().id()).getLearnerId()).isEqualTo(1);
        courses.publish(courseId,1L,true);
        when(questions.fitsPrepared(any(),any(),any(),any())).thenReturn(false);
        when(retrieval.search(any(),any(),any(Runnable.class))).thenThrow(new VideoKnowledgeIndexService.KnowledgeIndexNotReadyException("not ready"));
        assertThatThrownBy(() -> dispatch.dispatch(10L,2L,new AssistantDispatchRequest("解释", "QUICK", null, courseId, null)))
                .isInstanceOf(VideoConversationService.ConversationNotReadyException.class).hasMessageContaining("课程作者");
        verifyNoInteractions(preparation,mq,index);
    }

    @Test void rollbackDoesNotLeaveHalfACreatedRunOrTimeline() {
        courses.publish(courseId,1L,true);
        var b=conversations.create(10L,2L,courseId);
        var invalid=new VideoAgentRunMessage("invalid", 10L, b.id(), 3L, courseId, "notes", "v", com.example.server.service.StudyTaskFixtures.video(10L,3L,b.id(),courseId,"notes","v"));
        assertThatThrownBy(() -> runs.createQueuedRun(invalid)).isInstanceOf(NoSuchElementException.class);
        assertThat(store.listMessages(b.id(),true).messages()).isEmpty();
        assertThat(runs.findById("invalid")).isEmpty();
    }
    @Test void aMidWriteFailureRollsBackMessagesAndTheirNewConversation() {
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,username,password) VALUES (99,'rollback-learner','test-hash')");
            jdbc.update("INSERT INTO media_files(id,user_id,filename) VALUES (99,99,'rollback source')");
            var conversation = conversations.create(99L,99L,null);
            // The citation fails only after both messages have been inserted.
            store.savePreparedTurn(store.getRequired(conversation.id()),tasks.prepare(99L,99L,conversation.id(),null,null,"question"),new VideoQuestionResponse("answer",
                    List.of(new VideoQuestionCitationResponse(null,0L,1L,"invalid citation"))));
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=99",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations WHERE learner_id=99",Integer.class)).isZero();
    }

    private VideoAgentRunMessage command(String id,Long conversationId,Long learnerId) {
        return new VideoAgentRunMessage(id,10L,conversationId,learnerId,courseId,"notes","v",tasks.prepare(10L,learnerId,conversationId,courseId,null,"notes"));
    }
    private String resultJson() { return "{\"title\":\"Saved notes\",\"coreConclusion\":\"conclusion\",\"coreEvidenceRefs\":[],\"knowledgePoints\":[],\"quiz\":[],\"gaps\":[]}"; }
    private String citationsJson() { return "[{\"ref\":\"TRANSCRIPT:99\",\"type\":\"TRANSCRIPT\",\"id\":99,\"startMs\":100,\"endMs\":200,\"text\":\"secret source text\"}]"; }
}

package com.example.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson2.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.example.server.dto.*;
import com.example.server.entity.*;
import com.example.server.mapper.*;
import com.example.server.service.ai.*;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.context.*;
import com.example.server.service.external.ExternalVideoImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
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

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import javax.sql.DataSource;

/**
 * Real MySQL transactions, authorization, native HTTP protocol, ledger, dispatcher and delivery.
 */
@SpringJUnitConfig(StudyAgentRuntimeMySqlTest.Config.class)
@EnabledIfEnvironmentVariable(named = "STUDY_P4_MYSQL_URL", matches = ".+")
class StudyAgentRuntimeMySqlTest {
    @Configuration
    @EnableTransactionManagement
    @Import({
        StudyAccessService.class,
        CourseService.class,
        MediaService.class,
        VideoConversationStore.class,
        VideoConversationService.class,
        VideoAgentRunService.class,
        VideoAgentSubmissionService.class,
        AssistantDispatchService.class,
        AssistantModeRouter.class,
        StudyTaskPreparationService.class,
        StudyEvidenceService.class,
        StudyRunControl.class,
        StudyArtifactService.class,
        StudyAgentToolDispatcher.class,
        StudyAgentRuntime.class,
        VideoAgentWorkflowService.class
    })
    static class Config {
        @Bean
        DataSource dataSource() {
            String url = System.getenv("STUDY_P4_MYSQL_URL");
            if (!url.matches("jdbc:mysql://[^/]+/vediomind_study_test_[A-Za-z0-9_]+(\\?.*)?"))
                throw new IllegalArgumentException("Isolated study database required");
            var db =
                    new DriverManagerDataSource(
                            url,
                            System.getenv("AUTH_TEST_MYSQL_USER"),
                            System.getenv("AUTH_TEST_MYSQL_PASSWORD"));
            var flyway = Flyway.configure().dataSource(db).load();
            flyway.migrate();
            flyway.validate();
            return db;
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactions(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        SqlSessionFactory factory(DataSource ds) {
            var c = new MybatisConfiguration();
            c.setMapUnderscoreToCamelCase(true);
            c.setEnvironment(
                    new Environment("study-test", new SpringManagedTransactionFactory(), ds));
            for (var type :
                    List.of(
                            MediaFileMapper.class,
                            VideoConversationMapper.class,
                            VideoMessageMapper.class,
                            VideoMessageCitationMapper.class,
                            VideoAgentRunMapper.class,
                            VideoAgentStepMapper.class,
                            MediaVisualEvidenceMapper.class,
                            VideoKeyframeMapper.class)) c.addMapper(type);
            return new MybatisSqlSessionFactoryBuilder().build(c);
        }

        @Bean
        SqlSessionTemplate sql(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        MediaVisualEvidenceMapper manifestMapper(SqlSessionTemplate sql) {
            return sql.getMapper(MediaVisualEvidenceMapper.class);
        }

        @Bean
        VideoKeyframeMapper keyframeMapper(SqlSessionTemplate sql) {
            return sql.getMapper(VideoKeyframeMapper.class);
        }

        @Bean
        MediaFileMapper mediaMapper(SqlSessionTemplate sql) {
            return sql.getMapper(MediaFileMapper.class);
        }

        @Bean
        VideoConversationMapper conversationMapper(SqlSessionTemplate sql) {
            return sql.getMapper(VideoConversationMapper.class);
        }

        @Bean
        VideoMessageMapper messageMapper(SqlSessionTemplate sql) {
            return sql.getMapper(VideoMessageMapper.class);
        }

        @Bean
        VideoMessageCitationMapper citationMapper(SqlSessionTemplate sql) {
            return sql.getMapper(VideoMessageCitationMapper.class);
        }

        @Bean
        VideoAgentRunMapper runMapper(SqlSessionTemplate sql) {
            return sql.getMapper(VideoAgentRunMapper.class);
        }

        @Bean
        VideoAgentStepMapper stepMapper(SqlSessionTemplate sql) {
            return sql.getMapper(VideoAgentStepMapper.class);
        }

        @Bean
        ObjectStorageService storage() {
            return mock(ObjectStorageService.class);
        }

        @Bean
        ExternalVideoImportService imports() {
            return mock(ExternalVideoImportService.class);
        }

        @Bean
        StringRedisTemplate redis() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        ObjectMapper json() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        TranscriptService transcript() {
            return mock(TranscriptService.class);
        }

        @Bean
        ChapterService chapters() {
            return mock(ChapterService.class);
        }

        @Bean
        VisualEvidenceService visual() {
            return mock(VisualEvidenceService.class);
        }

        @Bean
        QdrantKnowledgeIndexService qdrant() {
            return mock(QdrantKnowledgeIndexService.class);
        }

        @Bean
        RedissonClient locks() throws Exception {
            var client = mock(RedissonClient.class);
            var lock = mock(RLock.class);
            when(client.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            return client;
        }

        @Bean
        com.example.server.service.context.VideoContextService contexts() {
            return mock(com.example.server.service.context.VideoContextService.class);
        }

        @Bean
        com.example.server.service.ai.AiPromptBudget budget() {
            return new com.example.server.service.ai.AiPromptBudget(64000, 8192);
        }

        @Bean
        VideoQuestionService questions() {
            return mock(VideoQuestionService.class);
        }

        @Bean
        VideoKnowledgeRetriever retrieval() {
            return mock(VideoKnowledgeRetriever.class);
        }

        @Bean
        VideoEvidenceContextService evidence() {
            return new VideoEvidenceContextService(3, 30);
        }

        @Bean
        KnowledgeIndexPreparationService preparation() {
            return mock(KnowledgeIndexPreparationService.class);
        }

        @Bean
        VideoKnowledgeIndexService index() {
            return mock(VideoKnowledgeIndexService.class);
        }

        @Bean
        RocketMQTemplate messages() {
            return mock(RocketMQTemplate.class);
        }

        @Bean
        AiRateLimitService quota() {
            return mock(AiRateLimitService.class);
        }

        @Bean
        com.example.server.service.ai.AiCallLedger ledger(
                JdbcTemplate jdbc,
                org.springframework.transaction.PlatformTransactionManager manager,
                StudyRunControl control,
                StudyTaskPreparationService tasks,
                AiRateLimitService rates) {
            return new com.example.server.service.ai.AiCallLedger(
                    jdbc,
                    manager,
                    control,
                    tasks,
                    new com.example.server.service.ai.AiRetryExecutor(rates));
        }

        @Bean(destroyMethod = "close")
        Provider provider() throws Exception {
            return new Provider();
        }

        @Bean
        DeepSeekChatClient model(
                Provider provider,
                com.example.server.service.ai.AiCallLedger ledger,
                com.example.server.service.ai.AiPromptBudget budget) {
            return new DeepSeekChatClient("test", provider.url(), ledger, budget);
        }

        @Bean
        VideoAgentEvidenceService oldEvidence() {
            return mock(VideoAgentEvidenceService.class);
        }
    }

    static class Provider implements AutoCloseable {
        HttpServer server;
        Queue<JSONObject> replies = new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<JSONObject> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile Runnable beforeReply = () -> {};

        Provider() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/chat/completions",
                    exchange -> {
                        requests.add(
                                JSON.parseObject(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8)));
                        beforeReply.run();
                        var message = replies.poll();
                        var response =
                                JSONObject.of(
                                        "choices",
                                        List.of(
                                                JSONObject.of(
                                                        "finish_reason",
                                                        "tool_calls",
                                                        "message",
                                                        message)),
                                        "usage",
                                        JSONObject.of(
                                                "prompt_tokens", 300, "completion_tokens", 80));
                        byte[] bytes =
                                response.toJSONString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        public void close() {
            server.stop(0);
        }

        void reset() {
            replies.clear();
            requests.clear();
            beforeReply = () -> {};
        }

        void call(String name, Object args) {
            replies.add(message("call-" + UUID.randomUUID(), name, args));
        }

        static JSONObject message(String id, String name, Object args) {
            var m =
                    JSONObject.of(
                            "role",
                            "assistant",
                            "tool_calls",
                            List.of(
                                    JSONObject.of(
                                            "id",
                                            id,
                                            "type",
                                            "function",
                                            "function",
                                            JSONObject.of(
                                                    "name",
                                                    name,
                                                    "arguments",
                                                    JSON.toJSONString(args)))));
            m.put("content", null);
            return m;
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CourseService courses;
    @Autowired StudyTaskPreparationService tasks;
    @Autowired VideoContextService contexts;
    @Autowired VideoKnowledgeIndexService indexes;
    @Autowired VideoKnowledgeRetriever retriever;
    @Autowired StudyRunControl control;
    @Autowired VideoAgentRunService runs;
    @Autowired VideoAgentWorkflowService workflow;
    @Autowired VideoConversationService conversations;
    @Autowired StudyArtifactService artifacts;
    @Autowired StudyAgentToolDispatcher dispatcher;
    @Autowired AiCallLedger ledger;
    @Autowired Provider provider;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    Long courseId, sectionId;
    PreparedStudyTask task;
    List<VideoAgentCitation> originals;

    @BeforeEach
    void fixture() {
        provider.reset();
        reset(contexts, indexes, retriever);
        for (var table :
                List.of(
                        "study_artifacts",
                        "study_run_events",
                        "ai_call_attempts",
                        "study_ai_requests",
                        "video_agent_steps",
                        "video_message_citations",
                        "video_messages",
                        "video_agent_runs",
                        "video_conversations",
                        "course_materials",
                        "course_sections",
                        "courses",
                        "media_files",
                        "users")) jdbc.update("DELETE FROM " + table);
        jdbc.update(
                "INSERT INTO users(id,username,password) VALUES"
                        + " (1,'author','hash'),(2,'student','hash'),(3,'other','hash')");
        for (long id : List.of(10L, 11L, 12L))
            jdbc.update(
                    "INSERT INTO media_files(id,user_id,filename,status,analysis_mode) VALUES"
                            + " (?,1,?,'COMPLETED','STUDY')",
                    id,
                    "lecture-" + id);
        when(contexts.currentVersion(anyLong())).thenReturn("t1-v1-p1");
        when(contexts.currentVisualState(anyLong())).thenReturn("AVAILABLE");
        courseId = courses.create(1L, "事务课程", "").course().id();
        sectionId = courses.addSection(courseId, 1L, "第三章 事务", 0).sections().getFirst().id();
        for (long id : List.of(10L, 11L)) {
            courses.addMaterial(courseId, sectionId, id, 1L, (int) id);
            var c = StudyTaskContextMySqlTest.context(id);
            when(contexts.build(id)).thenReturn(c);
            when(indexes.requireReady(id))
                    .thenReturn(new VideoKnowledgeIndexService.IndexSnapshot(c, List.of(), ""));
        }
        courses.publish(courseId, 1L, true);
        var conversation = conversations.create(10L, 2L, courseId);
        task =
                tasks.forMode(
                        tasks.prepare(
                                10L,
                                2L,
                                conversation.id(),
                                courseId,
                                new PreparedStudyTask.Scope("SECTION", sectionId, List.of()),
                                "整理第三章事务学习笔记"),
                        "DEEP");
        var hit =
                new VideoKnowledgeRetriever.KnowledgeSearchHit(
                        1, 300000, 600000, "", List.of(), 1, List.of(43L), List.of());
        when(retriever.searchMaterials(anyList(), anyString(), any(Runnable.class)))
                .thenAnswer(
                        invocation -> {
                            ledger.exchange(
                                    AiRateLimitService.ALIYUN_EMBEDDING,
                                    "RETRIEVAL",
                                    JSONObject.of("input", invocation.getArgument(1)),
                                    () -> {},
                                    null,
                                    d ->
                                            new AiCallLedger.WireResponse(
                                                    200, "{\"usage\":{\"total_tokens\":10}}"));
                            return List.of(List.of(hit), List.of(hit));
                        });
        originals =
                List.of(
                        new VideoAgentCitation(
                                "10:TRANSCRIPT:43:t1-v1-p1",
                                "TRANSCRIPT",
                                43L,
                                301000,
                                302000,
                                "核心定义",
                                10L,
                                "t1-v1-p1"),
                        new VideoAgentCitation(
                                "11:TRANSCRIPT:43:t1-v1-p1",
                                "TRANSCRIPT",
                                43L,
                                301000,
                                302000,
                                "核心定义",
                                11L,
                                "t1-v1-p1"));
        runs.createQueuedRun(
                new VideoAgentRunMessage(
                        "run",
                        10L,
                        conversation.id(),
                        2L,
                        courseId,
                        task.goal(),
                        "t1-v1-p1",
                        task));
    }

    JSONObject note() {
        return JSON.parseObject(
                JSON.toJSONString(
                        new VideoAgentResult(
                                "事务笔记",
                                "核心定义及前提",
                                originals.stream().map(VideoAgentCitation::ref).toList(),
                                List.of(
                                        new VideoAgentResult.KnowledgePoint(
                                                "共同定义",
                                                "两段视频讲解事务定义",
                                                originals.stream()
                                                        .map(VideoAgentCitation::ref)
                                                        .toList())),
                                List.of(),
                                List.of())));
    }

    void script() {
        provider.call("read_skill", Map.of("name", "course-notes"));
        provider.call("get_course_structure", Map.of());
        provider.call("search_course_evidence", Map.of("query", "事务定义"));
        provider.call(
                "read_evidence",
                Map.of("refs", originals.stream().map(VideoAgentCitation::ref).toList()));
        provider.call(
                "save_note",
                Map.of(
                        "slotId",
                        task.deliverables().getFirst().slotId(),
                        "note",
                        note(),
                        "coverageMaterialIds",
                        List.of(10L, 11L)));
    }

    @Test
    void nativeCrossVideoTaskDeliversExactlyOnePrivateNoteAndHonestUsage() {
        script();
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStatus()).isEqualTo("SUCCEEDED");
        assertThat(artifacts.list("run", 2L))
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.coverageMaterialIds()).containsExactly(10L, 11L);
                            assertThat(a.citations()).hasSize(2);
                        });
        assertThatThrownBy(() -> artifacts.list("run", 3L))
                .isInstanceOf(NoSuchElementException.class);
        workflow.process("run", 0);
        assertThat(provider.requests).hasSize(5);
        String notes = StudyAgentSkills.bundled().read("course-notes").getString("instructions");
        String qa = StudyAgentSkills.bundled().read("video-qa").getString("instructions");
        assertThat(JSON.toJSONString(provider.requests.getFirst()))
                .contains("course-notes", "video-qa")
                .doesNotContain(JSON.toJSONString(notes), JSON.toJSONString(qa), "## 执行步骤");
        assertThat(
                        JSON.parseObject(
                                        provider.requests
                                                .get(1)
                                                .getJSONArray("messages")
                                                .getJSONObject(3)
                                                .getString("content"))
                                .getString("instructions"))
                .isEqualTo(notes);
        assertThat(JSON.toJSONString(provider.requests.get(1)))
                .doesNotContain(JSON.toJSONString(qa));
        assertThat(
                        provider.requests
                                .getFirst()
                                .getJSONArray("messages")
                                .getJSONObject(1)
                                .getString("content"))
                .endsWith(runs.getRequired("run").getPreparedTask());
        assertThat(
                        provider.requests
                                .get(1)
                                .getJSONArray("messages")
                                .getJSONObject(3)
                                .getString("tool_call_id"))
                .isEqualTo(
                        provider.requests
                                .get(1)
                                .getJSONArray("messages")
                                .getJSONObject(2)
                                .getJSONArray("tool_calls")
                                .getJSONObject(0)
                                .getString("id"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ai_call_attempts WHERE run_id='run'",
                                Integer.class))
                .isEqualTo(6);
        assertThat(control.events("run", 2L, 0))
                .extracting(e -> e.get("type"))
                .contains("ARTIFACT_CANDIDATE_SAVED", "ARTIFACT_DELIVERED", "TASK_COMPLETED");
        assertThat(JSON.toJSONString(control.events("run", 2L, 0)))
                .doesNotContain("核心定义及前提", "instructions", "test-key");
    }

    @Test
    void candidateReceiptReplayAndCrashBeforeObservationNeverWriteTwiceOrOverwriteAnEdit() {
        long epoch = runs.beginAttempt("run", 0).orElseThrow();
        Map<String, VideoAgentCitation> read = new LinkedHashMap<>();
        originals.forEach(e -> read.put(e.ref(), e));
        try (var context = StudyExecutionContext.open(task, "run", epoch)) {
            var one =
                    artifacts.saveCandidate(
                            "run",
                            task.deliverables().getFirst().slotId(),
                            note(),
                            List.of(10L, 11L),
                            read);
            assertThat(
                            artifacts.saveCandidate(
                                    "run",
                                    task.deliverables().getFirst().slotId(),
                                    note(),
                                    List.of(11L, 10L),
                                    read))
                    .isEqualTo(one);
            assertThat(artifacts.list("run", 2L)).isEmpty();
            var changed = note();
            changed.put("title", "不同内容");
            assertThatThrownBy(
                            () ->
                                    artifacts.saveCandidate(
                                            "run",
                                            task.deliverables().getFirst().slotId(),
                                            changed,
                                            List.of(10L, 11L),
                                            read))
                    .isInstanceOf(GovernanceException.class)
                    .hasMessageContaining("不能覆盖");
        }
        assertThat(control.recoverRunning("run", 0)).isTrue();
        workflow.process("run", 1);
        assertThat(provider.requests).isEmpty();
        assertThat(artifacts.list("run", 2L)).hasSize(1);
        var artifact = artifacts.list("run", 2L).getFirst();
        var changed = note();
        changed.put("title", "学生亲自修改");
        var edited = artifacts.edit(artifact.id(), 2L, new StudyArtifactService.Edit(1, changed));
        assertThat(edited.version()).isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                artifacts.edit(
                                        artifact.id(),
                                        2L,
                                        new StudyArtifactService.Edit(1, note())))
                .isInstanceOf(GovernanceException.class);
        assertThatThrownBy(
                        () ->
                                artifacts.edit(
                                        artifact.id(),
                                        3L,
                                        new StudyArtifactService.Edit(2, note())))
                .isInstanceOf(NoSuchElementException.class);
        workflow.process("run", 1);
        assertThat(artifacts.list("run", 2L).getFirst().content().title()).isEqualTo("学生亲自修改");
        courses.publish(courseId, 1L, false);
        assertThat(artifacts.list("run", 2L).getFirst().citations())
                .allSatisfy(
                        c -> {
                            assertThat(c.available()).isFalse();
                            assertThat(c.evidence().text()).isEmpty();
                        });
    }

    @Test
    void forgedToolAndForgedReferenceStopWithoutAnyArtifact() {
        provider.call("execute_sql", Map.of("sql", "delete from users"));
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_artifacts", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(3);
    }

    @Test
    void repeatedInvalidArgumentsHaveAFiniteCorrectionBudget() {
        for (int i = 0; i < 3; i++)
            provider.call("read_skill", Map.of("name", "course-notes", "learnerId", 3));
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("INVALID_TOOL_ARGUMENTS");
        assertThat(provider.requests).hasSize(3);
        assertThat(artifacts.list("run", 2L)).isEmpty();
        verifyNoInteractions(retriever);
    }

    @Test
    void arbitraryJsonTextCannotExecuteToolsAndEmptyContentWithNativeToolsCan() {
        provider.replies.add(
                JSONObject.of(
                        "role", "assistant", "content", "{\"tool\":\"save_note\",\"args\":{}}"));
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("NO_PROGRESS");
        assertThat(artifacts.list("run", 2L)).isEmpty();
    }

    @Test
    void cancellationDuringProviderCallAccountsUsageAndCannotPublish() {
        provider.call("read_skill", Map.of("name", "course-notes"));
        provider.beforeReply = () -> control.cancel("run", 2L);
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStatus()).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT input_tokens FROM study_ai_requests WHERE run_id='run'",
                                Long.class))
                .isEqualTo(300);
        assertThat(artifacts.list("run", 2L)).isEmpty();
    }

    @Test
    void changingANonAnchorMaterialBlocksTheNextPaidCall() {
        provider.call("read_skill", Map.of("name", "course-notes"));
        provider.beforeReply = () -> when(contexts.currentVersion(11L)).thenReturn("changed");
        workflow.process("run", 0);
        assertThat(provider.requests).hasSize(1);
        assertThat(artifacts.list("run", 2L)).isEmpty();
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("TASK_RESOURCE_CHANGED");
    }

    @Test
    void transactionFailureBetweenVisibilityAndCompletionRollsBackAndRecovers() {
        script();
        jdbc.execute(
                "CREATE TRIGGER fail_delivery BEFORE UPDATE ON video_agent_runs FOR EACH ROW BEGIN"
                        + " IF NEW.status='SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET"
                        + " MESSAGE_TEXT='synthetic delivery failure'; END IF; END");
        try {
            assertThatThrownBy(() -> workflow.process("run", 0))
                    .isInstanceOf(RuntimeException.class);
            assertThat(artifacts.list("run", 2L)).isEmpty();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM study_artifacts WHERE run_id='run'",
                                    String.class))
                    .isEqualTo("CANDIDATE");
            assertThat(runs.getRequired("run").getResultJson()).isNull();
            assertThat(control.events("run", 2L, 0))
                    .extracting(e -> e.get("type"))
                    .doesNotContain("ARTIFACT_DELIVERED", "TASK_COMPLETED");
        } finally {
            jdbc.execute("DROP TRIGGER fail_delivery");
        }
        assertThat(control.recoverRunning("run", 0)).isTrue();
        workflow.process("run", 1);
        assertThat(artifacts.list("run", 2L)).hasSize(1);
        assertThat(provider.requests).hasSize(5);
        assertThat(runs.getRequired("run").getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void forgedReadAndUnknownBatchToolCannotCommitEvenAPrecedingSave() {
        var good =
                Provider.message(
                        "save",
                        "save_note",
                        Map.of(
                                "slotId",
                                task.deliverables().getFirst().slotId(),
                                "note",
                                note(),
                                "coverageMaterialIds",
                                List.of(10L, 11L)));
        var forged =
                Provider.message(
                        "evil",
                        "read_evidence",
                        Map.of("refs", List.of("12:TRANSCRIPT:43:t1-v1-p1")));
        good.getJSONArray("tool_calls").addAll(forged.getJSONArray("tool_calls"));
        provider.replies.add(good);
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_artifacts", Integer.class))
                .isZero();
        verifyNoInteractions(retriever);
    }

    @Test
    void partialCoverageCannotClaimCompleteCourseNotes() {
        long epoch = runs.beginAttempt("run", 0).orElseThrow();
        var first = originals.getFirst();
        var partial = note();
        partial.put("coreEvidenceRefs", List.of(first.ref()));
        partial.getJSONArray("knowledgePoints")
                .getJSONObject(0)
                .put("evidenceRefs", List.of(first.ref()));
        try (var scope = StudyExecutionContext.open(task, "run", epoch)) {
            artifacts.saveCandidate(
                    "run",
                    task.deliverables().getFirst().slotId(),
                    partial,
                    List.of(10L),
                    Map.of(first.ref(), first));
            assertThat(artifacts.deliver("run", null, null)).isTrue();
        }
        assertThat(runs.getRequired("run").getStatus()).isEqualTo("PARTIAL");
        assertThat(artifacts.list("run", 2L)).hasSize(1);
        assertThat(control.events("run", 2L, 0))
                .extracting(e -> e.get("type"))
                .doesNotContain("TASK_COMPLETED");
    }

    @Test
    void repeatedCallsStopAndWithdrawalOfAnotherMaterialFencesTheEntireTask() {
        provider.call("read_skill", Map.of("name", "course-notes"));
        provider.call("read_skill", Map.of("name", "course-notes"));
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("NO_PROGRESS");
        assertThat(provider.requests).hasSize(2);
    }

    @Test
    void nonAnchorWithdrawalStopsAnActiveCrossVideoTaskImmediately() {
        var material =
                courses.detail(courseId, 1L).sections().getFirst().materials().stream()
                        .filter(m -> m.mediaId() == 11L)
                        .findFirst()
                        .orElseThrow();
        courses.removeMaterial(courseId, material.id(), 1L);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("TASK_SOURCE_UNAVAILABLE");
        workflow.process("run", 0);
        assertThat(provider.requests).isEmpty();
    }

    @Test
    void routingProfileTransitionCarriesPaidCallsAndNeverRenewsTime() {
        var prepared =
                tasks.prepare(
                        10L,
                        2L,
                        task.conversationId(),
                        courseId,
                        new PreparedStudyTask.Scope("SECTION", sectionId, List.of()),
                        "整理笔记");
        try (var scope = ledger.open(prepared);
                var purpose = StudyExecutionContext.purpose("ROUTING")) {
            ledger.exchange(
                    AiRateLimitService.DEEPSEEK,
                    "GENERATION",
                    JSONObject.of("max_tokens", 20, "messages", List.of("route")),
                    () -> {},
                    null,
                    d ->
                            new AiCallLedger.WireResponse(
                                    200,
                                    "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}"));
        }
        var before =
                jdbc.queryForMap(
                        "SELECT * FROM study_ai_requests WHERE id=?", prepared.requestId());
        ledger.selectProfile(tasks.forMode(prepared, "DEEP"));
        var after =
                jdbc.queryForMap(
                        "SELECT * FROM study_ai_requests WHERE id=?", prepared.requestId());
        assertThat(after.get("generation_calls")).isEqualTo(before.get("generation_calls"));
        assertThat(after.get("accounted_tokens")).isEqualTo(before.get("accounted_tokens"));
        assertThat(after.get("deadline_at")).isEqualTo(before.get("deadline_at"));
        assertThat(((Number) after.get("max_generation_calls")).intValue()).isEqualTo(8);
        assertThat(((Number) after.get("max_accounted_tokens")).longValue()).isEqualTo(96000);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 3, 4})
    void recoveryAfterAResponseButBeforeDecisionCheckpointReusesIdenticalNativeHistory(
            int crashRound) {
        script();
        provider.beforeReply =
                () -> {
                    if (provider.requests.size() == crashRound + 1)
                        jdbc.execute(
                                "CREATE TRIGGER fail_decision BEFORE INSERT ON video_agent_steps"
                                        + " FOR EACH ROW BEGIN IF NEW.stage='AGENT_DECISION' AND"
                                        + " NEW.round_no="
                                        + crashRound
                                        + " THEN SIGNAL SQLSTATE '45000' SET"
                                        + " MESSAGE_TEXT='synthetic checkpoint failure'; END IF;"
                                        + " END");
                };
        try {
            assertThatThrownBy(() -> workflow.process("run", 0))
                    .isInstanceOf(RuntimeException.class);
            assertThat(provider.requests).hasSize(crashRound + 1);
            assertThat(artifacts.list("run", 2L)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_decision");
            provider.beforeReply = () -> {};
        }
        assertThat(control.recoverRunning("run", 0)).isTrue();
        workflow.process("run", 1);
        assertThat(runs.getRequired("run").getStatus()).isEqualTo("SUCCEEDED");
        assertThat(provider.requests).hasSize(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ai_call_attempts WHERE run_id='run'",
                                Integer.class))
                .isEqualTo(6);
        assertThat(artifacts.list("run", 2L)).hasSize(1);
    }

    @Test
    void changedRuntimeResourcesAreRejectedBeforeAnyPaidExecution() {
        var snapshot = JSON.parseObject(JSON.toJSONString(task));
        snapshot.getJSONObject("resourceSnapshot").put("prompt", "changed");
        jdbc.update(
                "UPDATE video_agent_runs SET prepared_task=? WHERE id='run'",
                snapshot.toJSONString());
        assertThatThrownBy(() -> workflow.process("run", 0))
                .isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        assertThat(provider.requests).isEmpty();
        verifyNoInteractions(retriever);
    }

    @Test
    void aBatchOverTheToolBudgetCannotExecuteAnyTool() {
        var message = Provider.message("id0", "read_skill", Map.of("name", "course-notes"));
        for (int i = 1; i < 11; i++)
            message.getJSONArray("tool_calls")
                    .addAll(
                            Provider.message("id" + i, "read_skill", Map.of("name", "course-notes"))
                                    .getJSONArray("tool_calls"));
        provider.replies.add(message);
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("TASK_BUDGET_EXHAUSTED");
        assertThat(provider.requests).hasSize(1);
        assertThat(artifacts.list("run", 2L)).isEmpty();
        verifyNoInteractions(retriever);
    }

    @Test
    void decisionRoundsAreBoundedEvenWhenEachToolRequestIsDifferent() {
        provider.call("read_skill", Map.of("name", "course-notes"));
        provider.call("get_course_structure", Map.of());
        provider.call("get_learning_state", Map.of());
        provider.call("read_skill", Map.of("name", "video-qa"));
        provider.call("search_course_evidence", Map.of("query", "事务定义"));
        provider.call("search_course_evidence", Map.of("query", "事务条件"));
        workflow.process("run", 0);
        assertThat(runs.getRequired("run").getStopCode()).isEqualTo("TASK_BUDGET_EXHAUSTED");
        assertThat(provider.requests).hasSize(6);
        assertThat(artifacts.list("run", 2L)).isEmpty();
    }
}

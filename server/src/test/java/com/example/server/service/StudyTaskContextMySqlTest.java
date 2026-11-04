package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.*;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.context.VideoContext;
import com.example.server.service.context.VideoContextService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(StudyAuthorizationMySqlTest.Config.class)
@EnabledIfEnvironmentVariable(named="STUDY_TEST_MYSQL_URL", matches=".+")
@Transactional
class StudyTaskContextMySqlTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired CourseService courses;
    @Autowired MediaService media;
    @Autowired ObjectStorageService storage;
    @Autowired StudyTaskPreparationService tasks;
    @Autowired StudyEvidenceService evidence;
    @Autowired VideoContextService contexts;
    @Autowired VideoKnowledgeIndexService indexes;
    @Autowired VideoKnowledgeRetriever retriever;
    @Autowired VideoConversationService conversations;
    @Autowired VideoConversationStore store;
    @Autowired AssistantDispatchService dispatch;
    @Autowired VideoQuestionService questions;
    @Autowired AiRateLimitService quota;
    @Autowired RocketMQTemplate mq;
    @Autowired DeepSeekChatClient model;
    Long courseId, sectionId, otherSection;

    @BeforeEach void course() {
        reset(contexts,indexes,retriever,questions,quota,mq,model);
        when(contexts.currentVisualState(anyLong())).thenReturn("AVAILABLE");
        when(contexts.currentVersion(anyLong())).thenReturn("t1-v1-p1");
        for (long id:List.of(1L,2L,3L)) jdbc.update("INSERT INTO users(id,username,password) VALUES (?,?,'hash')",id,"user-"+id);
        for (long id:List.of(10L,11L,12L,13L)) jdbc.update("INSERT INTO media_files(id,user_id,filename,status,analysis_mode) VALUES (?,1,?,'COMPLETED','STUDY')",id,"视频"+id);
        courseId=courses.create(1L,"课程","").course().id();
        sectionId=courses.addSection(courseId,1L,"第三章 事务",0).sections().getFirst().id();
        otherSection=courses.addSection(courseId,1L,"第四章 索引",1).sections().getLast().id();
        courses.addMaterial(courseId,sectionId,10L,1L,0); courses.addMaterial(courseId,sectionId,11L,1L,1);
        courses.addMaterial(courseId,otherSection,12L,1L,0); courses.publish(courseId,1L,true);
        when(contexts.build(10L)).thenReturn(context(10L)); when(contexts.build(11L)).thenReturn(context(11L));
    }
    PreparedStudyTask prepare(String goal) { return tasks.prepare(10L,2L,null,courseId,null,goal); }
    static VideoContext context(Long media) {
        var modality=new VideoContext.Modality("AVAILABLE",1,2,2,null);
        return new VideoContext(media,"t1-v1-p1","TEST",modality,modality,
                List.of(new VideoContext.TranscriptEvidence(42L,299000,300000,"前置条件"),
                        new VideoContext.TranscriptEvidence(43L,301000,302000,"核心定义")),
                List.of(new VideoContext.VisualEvidence(42L,301000,"frame","SUCCEEDED",1.0,"板书定义")));
    }

    @Autowired com.example.server.mapper.MediaFileMapper mediaMapper;
    @Autowired com.example.server.mapper.MediaVisualEvidenceMapper manifests;
    @Autowired com.example.server.mapper.VideoKeyframeMapper frames;
    @Autowired TranscriptService transcripts;

    @Test void versionChecksReadCurrentDatabaseRowsInsteadOfMyBatisLocalCache() {
        var realContexts = new VideoContextService(mediaMapper,transcripts,manifests,frames);
        var realTasks = new StudyTaskPreparationService(new StudyAccessService(jdbc),courses,realContexts,
                new com.example.server.service.ai.AiPromptBudget(64000,8192),jdbc);
        var task = realTasks.prepare(10L,2L,null,courseId,null,"第三章定义");
        // Warm both mapper caches, then mutate real metadata via SQL in the same transaction.
        mediaMapper.selectById(11L); manifests.selectById(11L);
        jdbc.update("UPDATE media_files SET transcript_revision=transcript_revision+1 WHERE id=11");
        var first = task;
        assertThatThrownBy(() -> realTasks.assertCurrent(first)).isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        task = realTasks.prepare(10L,2L,null,courseId,null,"第三章定义");
        var frozen = task;
        jdbc.update("INSERT INTO media_visual_evidence(media_id,status,processor_version,revision,started_at) VALUES (11,'READY',1,1,NOW())");
        assertThatThrownBy(() -> realTasks.assertCurrent(frozen)).isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        var ready = realTasks.prepare(10L,2L,null,courseId,null,"第三章定义");
        jdbc.update("UPDATE media_visual_evidence SET status='PROCESSING' WHERE media_id=11");
        assertThatThrownBy(() -> realTasks.assertCurrent(ready)).isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
    }

    @Test void playbackSigningUsesTheExactCourseContextEvenWhenSourceIsPublishedElsewhere() {
        var other=courses.create(1L,"另一个课程","");
        var section=courses.addSection(other.course().id(),1L,"第一章",0).sections().getFirst().id();
        courses.addMaterial(other.course().id(),section,12L,1L,0); courses.publish(other.course().id(),1L,true);
        clearInvocations(storage);
        assertThatThrownBy(() -> media.getDetail(10L,2L,other.course().id())).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(storage);
        assertThat(media.getDetail(10L,2L,courseId).id()).isEqualTo(10);
    }

    @Test void thirdChapterIsFrozenBeforeAnyQueriesAndCannotIncludePrivateOrOtherSections() {
        var task=prepare("解释第三章的事务");
        assertThat(task.scope().type()).isEqualTo("SECTION");
        assertThat(task.materials()).extracting(PreparedStudyTask.Material::materialId).containsExactly(10L,11L);
        assertThat(task.courseVersion()).isEqualTo(courses.detail(courseId,2L).course().structureVersion());
        assertThat(task.contractVersion()).isEqualTo("study-task-v1");
        assertThat(task.deliverables()).singleElement().satisfies(s -> assertThat(s.slotId()).contains(task.requestId()));
        assertThatThrownBy(() -> tasks.prepare(10L,2L,null,courseId,new PreparedStudyTask.Scope("MATERIALS",null,List.of(13L)),"解释"))
                .isInstanceOf(NoSuchElementException.class);
        var privateCourse=courses.create(1L,"私有课","");
        var privateSection=courses.addSection(privateCourse.course().id(),1L,"第三章",0).sections().getFirst().id();
        assertThatThrownBy(() -> tasks.prepare(10L,2L,null,courseId,new PreparedStudyTask.Scope("SECTION",privateSection,List.of()),"解释"))
                .isInstanceOf(NoSuchElementException.class);
        verify(contexts,never()).build(12L); verify(contexts,never()).build(13L);
        verifyNoInteractions(indexes,retriever,questions,quota,mq,model);
    }

    @Test void clarificationDoesNotCreateAConversationOrActiveRunOrSpendQuota() {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations",Integer.class);
        for (String question : List.of("解释第五章", "比较第三章和第四章")) {
            var response=dispatch.dispatch(10L,2L,new AssistantDispatchRequest(question,"AUTO",null,courseId,null));
            assertThat(response.preparationStatus()).isEqualTo("NEEDS_INPUT");
            assertThat(response.task()).isNull(); assertThat(response.timeline()).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_conversations",Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_agent_runs",Integer.class)).isZero();
        verifyNoInteractions(indexes,retriever,questions,quota,mq,model);
    }

    @Test void fullOriginalsAndRetrievalUseCompositeIdentityAndKeepNeighborsWithinMaterial() {
        var task=prepare("第三章定义");
        var full=evidence.full(task);
        assertThat(full).hasSize(6);
        assertThat(full).extracting(c -> c.evidence().ref()).doesNotHaveDuplicates()
                .contains("10:TRANSCRIPT:42:t1-v1-p1","11:TRANSCRIPT:42:t1-v1-p1","10:OCR:42:t1-v1-p1");
        verifyNoInteractions(indexes,retriever);
        for (long id:List.of(10L,11L)) {
            var index=new VideoKnowledgeIndexService.IndexSnapshot(context(id),List.of(),"");
            when(indexes.requireReady(id)).thenReturn(index);
        }
        var hit = new VideoKnowledgeRetriever.KnowledgeSearchHit(1,300000,600000,"",List.of(),1,List.of(43L),List.of(42L));
        when(retriever.searchMaterials(argThat(values -> values.stream().map(i -> i.context().mediaId()).toList().equals(List.of(10L,11L))),
                eq("定义"),any(Runnable.class))).thenReturn(List.of(List.of(hit),List.of(hit)));
        var found=evidence.search(task,"定义");
        assertThat(found).extracting(c -> c.evidence().ref()).containsExactlyInAnyOrderElementsOf(full.stream().map(c -> c.evidence().ref()).toList());
        verify(indexes,never()).requireReady(12L); verify(indexes,never()).requireReady(13L);
        var chronological=found.stream().sorted(Comparator.comparingLong(c -> c.evidence().startMs())).toList();
        int anchor=java.util.stream.IntStream.range(0,chronological.size()).filter(i -> chronological.get(i).evidence().ref().startsWith("10:TRANSCRIPT:43:")).findFirst().orElseThrow();
        assertThat(VideoEvidenceContextService.questionWindow(chronological,anchor)).allSatisfy(c ->
                assertThat(c.evidence().materialId()).isEqualTo(10));
    }

    @Test void lateVersionOrStructureChangeCannotDeliverOrMixEvidence() {
        var conversation=conversations.create(10L,2L,courseId);
        var task=prepare("第三章定义").inConversation(conversation.id());
        when(contexts.currentVersion(11L)).thenReturn("t2-v1-p1");
        assertThatThrownBy(() -> evidence.search(task,"定义")).isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        assertThatThrownBy(() -> store.savePreparedTurn(store.getRequired(conversation.id()),task,new VideoQuestionResponse("late answer",List.of())))
                .isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM video_messages",Integer.class)).isZero();
        verifyNoInteractions(indexes,retriever,questions,model);
        when(contexts.currentVersion(11L)).thenReturn("t1-v1-p1");
        courses.addSection(courseId,1L,"第五章",2);
        assertThatThrownBy(() -> tasks.assertCurrent(task)).isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
    }

    @Test void courseQuestionUsesBoundedRetrievalOnlyAfterFullContextDoesNotFit() {
        var conversation=conversations.create(10L,2L,courseId);
        var task=prepare("第三章定义").inConversation(conversation.id());
        for (long id : List.of(10L,11L)) when(indexes.requireReady(id)).thenReturn(
                new VideoKnowledgeIndexService.IndexSnapshot(context(id),List.of(),""));
        var hit=new VideoKnowledgeRetriever.KnowledgeSearchHit(1,300000,600000,"",List.of(),1,List.of(43L),List.of());
        when(retriever.searchMaterials(anyList(),anyString(),any(Runnable.class))).thenReturn(List.of(List.of(hit),List.of(hit)));
        when(questions.fitsPrepared(eq(task),any(),any(),any())).thenReturn(false);
        when(questions.answerPrepared(eq(task),any(),any(),any(),eq(true))).thenReturn(new VideoQuestionResponse("有依据的局部回答",List.of()));
        conversations.askPrepared(task);
        verify(retriever).searchMaterials(argThat(values -> values.size()==2 && values.stream().allMatch(v -> List.of(10L,11L).contains(v.context().mediaId()))),anyString(),any(Runnable.class));
        verify(questions).answerPrepared(eq(task),argThat(values -> values.stream().anyMatch(c -> !c.core())),any(),any(),eq(true));
        verifyNoInteractions(mq,model);
    }

    @Test void budgetConfigurationChangeDoesNotSilentlyExpandAPreparedTask() {
        var task=prepare("第三章定义");
        var changed=new StudyTaskPreparationService(new StudyAccessService(jdbc),courses,contexts,
                new com.example.server.service.ai.AiPromptBudget(32000,4096),jdbc);
        assertThatThrownBy(() -> changed.assertCurrent(task)).isInstanceOf(StudyTaskPreparationService.SourceChangedException.class)
                .hasMessageContaining("预算配置");
        verifyNoInteractions(retriever,questions,mq,model);
    }

    @Test void crossVideoAnswerPersistsSnapshotAndMasksOnlyUnavailableSources() {
        var conversation=conversations.create(10L,2L,courseId);
        var task=prepare("第三章定义").inConversation(conversation.id());
        var citation=new VideoQuestionCitationResponse(42L,299000L,300000L,"原文",11L,"t1-v1-p1","11:TRANSCRIPT:42:t1-v1-p1","TRANSCRIPT",true);
        when(questions.fitsPrepared(eq(task),any(),any(),any())).thenReturn(true);
        when(questions.answerPrepared(eq(task),any(),any(),any(),eq(false))).thenReturn(new VideoQuestionResponse("回答",List.of(citation)));
        conversations.askPrepared(task);
        verifyNoInteractions(indexes,retriever,mq);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(prepared_task,'$.requestId')) FROM video_messages WHERE message_type='ASSISTANT'",String.class)).isEqualTo(task.requestId());
        var saved=conversations.messages(conversation.id(),2L).messages().getLast().citations().getFirst();
        assertThat(saved.materialId()).isEqualTo(11); assertThat(saved.sourceAvailable()).isTrue();
        assertThatThrownBy(() -> conversations.messages(conversation.id(),3L)).isInstanceOf(NoSuchElementException.class);
        var material=courses.detail(courseId,1L).sections().getFirst().materials().getLast();
        courses.removeMaterial(courseId,material.id(),1L);
        saved=conversations.messages(conversation.id(),2L).messages().getLast().citations().getFirst();
        assertThat(saved.sourceAvailable()).isFalse(); assertThat(saved.text()).isNull();
        assertThat(conversations.messages(conversation.id(),2L).messages().getLast().content()).isEqualTo("回答");
        assertThat(conversations.messages(conversation.id(),2L).sourceAvailable()).isTrue();
    }
}

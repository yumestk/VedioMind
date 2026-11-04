package com.example.server.service;

import com.example.server.dto.*;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.impl.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparedQuestionContextTest {
    private final StudyTaskPreparationService tasks = mock(StudyTaskPreparationService.class);
    DeepSeekChatClient model=mock(DeepSeekChatClient.class);
    PreparedStudyTask task=PreparedStudyTask.create("request-1",2L,7L,1L,10L,1L,
            new PreparedStudyTask.Scope("SECTION",3L,List.of(10L,11L)),
            List.of(new PreparedStudyTask.Material(10L,1L,3L,"事务上","v1","AVAILABLE"),new PreparedStudyTask.Material(11L,2L,3L,"事务下","v1","AVAILABLE")),
            "解释第三章", "解释第三章",List.of("ANSWER"),"COURSE_MATERIAL_ONLY",PreparedStudyTask.Budget.standard(55296,8192,3,6,12));
    VideoEvidenceContextService.Candidate evidence(long material, String type, String text) {
        return new VideoEvidenceContextService.Candidate(new VideoAgentCitation(material+":"+type+":42:v1",type,42L,
                300000,301000,text,material,"v1"),true,0);
    }

    @Test void sameRawIdInDifferentVideosAndModalitiesMapsToDistinctServerOwnedCitations() {
        var service=new VideoQuestionService(model, new AiPromptBudget(64000,8192), tasks);
        when(model.chatWithinBudget(anyString(),anyList(),any())).thenAnswer(call -> {
            List<DeepSeekMessage> messages=call.getArgument(1);
            assertThat(messages.getLast().content()).contains("study-task-v1","COURSE_MATERIAL_ONLY","10:TRANSCRIPT:42:v1","11:TRANSCRIPT:42:v1","10:OCR:42:v1");
            return new DeepSeekChatClient.ChatResult("{\"answer\":\"事务说明\",\"evidenceIds\":[\"10:TRANSCRIPT:42:v1\",\"11:TRANSCRIPT:42:v1\",\"10:OCR:42:v1\"]}",10,10);
        });
        var result=service.answerPrepared(task,List.of(evidence(10,"TRANSCRIPT","定义"),evidence(11,"TRANSCRIPT","例子"),evidence(10,"OCR","板书")),List.of(),"",false);
        assertThat(result.citations()).extracting(VideoQuestionCitationResponse::materialId).containsExactly(10L,11L,10L);
        assertThat(result.citations()).extracting(VideoQuestionCitationResponse::evidenceRef).doesNotHaveDuplicates();
    }

    @Test void everyProviderAttemptRechecksFrozenSourceBeforeSending() {
        var service = new VideoQuestionService(model, new AiPromptBudget(64000,8192), tasks);
        doNothing().doThrow(new StudyTaskPreparationService.SourceChangedException("材料版本已变更"))
                .when(tasks).assertCurrent(task);
        when(model.chatWithinBudget(anyString(), anyList(), any())).thenAnswer(call -> {
            Runnable beforeAttempt = call.getArgument(2);
            beforeAttempt.run();
            beforeAttempt.run(); // Provider retry after source changed must stop before its request.
            throw new AssertionError("Changed source must prevent retry");
        });
        assertThatThrownBy(() -> service.answerPrepared(task, List.of(evidence(10,"TRANSCRIPT","source")), List.of(), "", false))
                .isInstanceOf(StudyTaskPreparationService.SourceChangedException.class);
        verify(tasks, times(2)).assertCurrent(task);
    }

    @Test void forgedOrStaleCitationIdentityIsRejected() {
        var service=new VideoQuestionService(model, new AiPromptBudget(64000,8192), tasks);
        for (String ref:List.of("42","12:TRANSCRIPT:42:v1","10:TRANSCRIPT:42:v0","10:OCR:42:v1")) {
            when(model.chatWithinBudget(anyString(),anyList(),any())).thenReturn(new DeepSeekChatClient.ChatResult(
                "{\"answer\":\"bad\",\"evidenceIds\":[\""+ref+"\"]}",10,10));
            assertThatThrownBy(() -> service.answerPrepared(task,List.of(evidence(10,"TRANSCRIPT","source")),List.of(),"",false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("citation");
        }
    }

    @Test void oversizedHistoryAndWorkReceiptAreDroppedBeforeRequiredTaskOrEvidence() {
        var budget=new AiPromptBudget(9000,512);
        var service=new VideoQuestionService(model, budget, tasks);
        when(model.chatWithinBudget(anyString(),anyList(),any())).thenAnswer(call -> {
            String system=call.getArgument(0); List<DeepSeekMessage> messages=call.getArgument(1);
            assertThat(budget.fits(system,messages)).isTrue();
            assertThat(messages.getLast().content()).contains("request-1","COURSE_MATERIAL_ONLY","10:TRANSCRIPT:42:v1","原文最后的必要条件");
            assertThat(messages.getLast().content()).doesNotContain("过大的旧笔记");
            return new DeepSeekChatClient.ChatResult("{\"answer\":\"condition\",\"evidenceIds\":[\"10:TRANSCRIPT:42:v1\"]}",10,10);
        });
        var history=List.of(new DeepSeekMessage("user","历史".repeat(3000)),new DeepSeekMessage("assistant","回答".repeat(3000)),
                new DeepSeekMessage("user","上一问"),new DeepSeekMessage("assistant","上一答"));
        var originals=List.of(evidence(10,"TRANSCRIPT","原文最后的必要条件"));
        assertThat(service.fitsPrepared(task,originals,history,"过大的旧笔记".repeat(2000))).isTrue();
        service.answerPrepared(task,originals,history,"过大的旧笔记".repeat(2000),false);
    }
}

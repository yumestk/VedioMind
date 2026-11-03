package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.*;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.impl.*;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoAgentWorkflowServiceTest {
    final VideoAgentRunService runs = mock(VideoAgentRunService.class);
    final VideoKnowledgeIndexService indexes = mock(VideoKnowledgeIndexService.class);
    final VideoAgentEvidenceService evidence = mock(VideoAgentEvidenceService.class);
    final DeepSeekChatClient chat = mock(DeepSeekChatClient.class);
    final VideoAgentWorkflowService workflow = new VideoAgentWorkflowService(runs, indexes, evidence, chat, new AiPromptBudget(32000,8192));
    final VideoAgentRun run = new VideoAgentRun();
    final Map<String,String> steps = new HashMap<>();
    final Instant deadline = Instant.now().plusSeconds(118);

    @BeforeEach void prepare() {
        run.setId("r"); run.setMediaId(34L); run.setGoal("学习图同构"); run.setContextVersion("v1");
        run.setGenerationCalls(0); run.setRetrievalCalls(0);
        when(runs.beginAttempt(eq("r"),anyInt())).thenReturn(true);
        when(runs.getRequired("r")).thenReturn(run);
        when(runs.requestDeadline("r")).thenReturn(deadline);
        when(indexes.requireReady(34L)).thenReturn(new VideoKnowledgeIndexService.IndexSnapshot(
                new VideoContext(34L,"v1","ASR",null,null,List.of(),List.of()),List.of(),"navigation"));
        when(runs.completedStep(anyString(),anyString(),anyInt())).thenAnswer(c -> Optional.ofNullable(steps.get(c.getArgument(1)+":"+c.getArgument(2))));
        doAnswer(c -> {steps.put(c.getArgument(1)+":"+c.getArgument(2),JSON.toJSONString(c.getArgument(4))); return null;})
                .when(runs).saveCompletedStep(anyString(),anyString(),anyInt(),any(),any());
        doAnswer(c -> {run.setResultJson(JSON.toJSONString(c.getArgument(1))); return null;})
                .when(runs).saveUsableResult(anyString(),any(),any());
        when(evidence.retrieve(any(),anyString(),any(),any())).thenReturn(bundle(1));
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenReturn(answer(notes(List.of())));
    }
    @Test void notesUseOneGenerationWithoutMandatoryPlannerOrQuiz() {
        workflow.process("r",0);
        verify(chat).chatWithinBudget(anyString(),anyList(),any(),eq(deadline),any());
        verify(runs).markSucceeded(eq("r"),argThat(n -> n.quiz().isEmpty()),any());
        verify(runs).saveCompletedStep(eq("r"),eq("RETRIEVAL_0"),eq(0),any(),any());
    }
    @Test void snapshotsSurviveGenerationFailureAndAreReusedAfterRedelivery() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any()))
                .thenThrow(new com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException("quota",60))
                .thenReturn(answer(notes(List.of())));
        assertThatThrownBy(() -> workflow.process("r",0)).hasMessage("quota");
        assertThat(steps).containsKey("RETRIEVAL_0:0");
        workflow.process("r",1);
        verify(evidence,times(1)).retrieve(any(),anyString(),any(),eq(deadline));
        verify(runs).markSucceeded(eq("r"),any(),any());
    }
    @Test void aFailedSupplementKeepsTheFirstValidatedNotes() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenReturn(answer(notes(List.of("需查找复杂度条件"))))
                .thenThrow(new AiPromptBudget.PromptBudgetExceededException("deadline"));
        when(evidence.retrieve(any(),anyString(),any(),any())).thenReturn(bundle(1),bundle(2));
        workflow.process("r",0);
        assertThat(run.getResultJson()).contains("需查找复杂度条件");
        verify(runs).finishStopped("r","deadline");
        verify(runs,never()).markSucceeded(any(),any(),any());
    }
    @Test void gapRetrievalStopsWhenNoNewEvidenceAppears() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenReturn(answer(notes(List.of("未解决条件"))));
        workflow.process("r",0);
        verify(chat,times(1)).chatWithinBudget(anyString(),anyList(),any(),any(),any());
        verify(runs).finishStopped(eq("r"),contains("未找到新增原文"));
    }
    @Test void invalidReferencesNeverBecomeUsableNotes() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenReturn(answer(notes(List.of()).replace("TRANSCRIPT:1","OCR:999")));
        workflow.process("r",0);
        verify(runs,never()).saveUsableResult(any(),any(),any());
        verify(runs).finishStopped(eq("r"),contains("引用有效"));
    }
    @Test void truncationWithoutEarlierNotesFailsAndIsNotAcceptedAsPartial() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenThrow(new AiPromptBudget.PromptBudgetExceededException("max-output-tokens"));
        workflow.process("r",0);
        verify(runs).finishStopped("r","max-output-tokens");
        verify(runs,never()).saveUsableResult(any(),any(),any());
    }
    @Test void anExplicitRequestToOmitQuizKeepsConciseNotes() {
        run.setGoal("整理学习笔记，不需要生成自测题");
        workflow.process("r",0);
        verify(runs).markSucceeded(eq("r"),argThat(n -> n.quiz().isEmpty()),any());
        assertThat(VideoAgentWorkflowService.requestsQuiz("不用笔记，只要自测题")).isTrue();
        assertThat(VideoAgentWorkflowService.requestsQuiz("notes without a quiz")).isFalse();
    }
    @Test void optionalQuizIsGeneratedOnlyOnRequest() {
        run.setGoal("生成3道自测题");
        var json=JSON.parseObject(notes(List.of()));
        json.put("quiz",List.of(Map.of("question","q","answer","a","evidenceRefs",List.of("TRANSCRIPT:1"))));
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenReturn(answer(json.toJSONString()));
        workflow.process("r",0);
        verify(runs).markSucceeded(eq("r"),argThat(n -> n.quiz().size()==1),any());
    }
    @Test void absenceOfRelevantOriginalsDoesNotProduceFabricatedNotes() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any()))
                .thenReturn(answer("{\"title\":\"未找到\",\"coreConclusion\":\"待查\",\"knowledgePoints\":[],\"gaps\":[\"Kubernetes调度证明\"]}"));
        workflow.process("r",0);
        verify(evidence,times(2)).retrieve(any(),anyString(),any(),any());
        verify(runs,never()).saveUsableResult(any(),any(),any());
        verify(runs).finishStopped(any(),any());
    }
    @Test void responseDiagnosticsPersistUsageIncludingTruncation() {
        when(chat.chatWithinBudget(anyString(),anyList(),any(),any(),any())).thenAnswer(c -> {
            java.util.function.Consumer<DeepSeekChatClient.ChatAttempt> observer=c.getArgument(4);
            run.setGenerationCalls(1);
            observer.accept(new DeepSeekChatClient.ChatAttempt("raw","{","reason","length",123,8192,200,"truncated",20));
            throw new AiPromptBudget.PromptBudgetExceededException("truncated");
        });
        workflow.process("r",0);
        verify(runs).addTokens("r",123,8192);
        assertThat(steps.get("GENERATION_ATTEMPT:1")).contains("reason","length","8192","raw");
    }
    @Test void completedNotesCanResumeWithoutRepeatingGeneration() {
        steps.put("RETRIEVAL_0:0",JSON.toJSONString(bundle(1))); steps.put("NOTES:0",notes(List.of()));
        workflow.process("r",1);
        verifyNoInteractions(chat,evidence);
        verify(runs).markSucceeded(eq("r"),any(),any());
    }
    static VideoAgentEvidenceService.EvidenceBundle bundle(long id) {
        return new VideoAgentEvidenceService.EvidenceBundle(List.of(),List.of(new VideoAgentCitation("TRANSCRIPT:"+id,"TRANSCRIPT",id,1000,2000,"original")));
    }
    static DeepSeekChatClient.ChatResult answer(String text) {return new DeepSeekChatClient.ChatResult(text,10,20);}
    static String notes(List<String> gaps) {
        return JSON.toJSONString(new VideoAgentResult("notes","core",List.of("TRANSCRIPT:1"),
                List.of(new VideoAgentResult.KnowledgePoint("point","explanation",List.of("TRANSCRIPT:1"))),List.of(),gaps));
    }
}

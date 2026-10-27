package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.dto.VideoAgentResult;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoAgentWorkflowServiceTest {

    private final VideoAgentRunService runService = mock(VideoAgentRunService.class);
    private final VideoKnowledgeIndexService indexService = mock(VideoKnowledgeIndexService.class);
    private final VideoAgentEvidenceService evidenceService = mock(VideoAgentEvidenceService.class);
    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final VideoAgentWorkflowService service = new VideoAgentWorkflowService(
            runService, indexService, evidenceService, chatClient, new AiPromptBudget(32000, 8192)
    );

    @Test
    void runsPlannerExecutorCriticAndPersistsValidatedResult() throws InterruptedException {
        prepareRun();
        when(runService.completedStep(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(evidenceService.retrieve(any(), eq("query"), any())).thenReturn(bundle());
        when(chatClient.chatWithinBudget(any(), any(), any())).thenReturn(
                chat(planJson()), chat(draftJson("TRANSCRIPT:1")), chat(criticJson(true))
        );

        service.process("run-1", 0);

        verify(evidenceService).retrieve(any(), eq("query"), any());
        verify(runService).markSucceeded(eq("run-1"), any(VideoAgentResult.class), any());
    }

    @Test
    void resumesCompletedPlannerAndExecutorWithoutRepeatingRetrieval() throws InterruptedException {
        prepareRun();
        VideoAgentWorkflowService.Plan plan = new VideoAgentWorkflowService.Plan(List.of(
                new VideoAgentWorkflowService.Subtask("S1", "title", "query", "outcome")
        ));
        VideoAgentWorkflowService.ExecutionOutput execution = new VideoAgentWorkflowService.ExecutionOutput(
                plan.subtasks().getFirst(),
                new VideoAgentWorkflowService.Draft("draft", List.of(
                        new VideoAgentWorkflowService.Claim("claim", List.of("TRANSCRIPT:1"))
                )),
                bundle().evidence()
        );
        when(runService.completedStep("run-1", "PLANNER", 0))
                .thenReturn(Optional.of(JSON.toJSONString(plan)));
        when(runService.completedStep("run-1", "EXECUTOR_1", 0))
                .thenReturn(Optional.of(JSON.toJSONString(execution)));
        when(runService.completedStep("run-1", "CRITIC", 0)).thenReturn(Optional.empty());
        when(chatClient.chatWithinBudget(any(), any(), any())).thenReturn(chat(criticJson(true)));

        service.process("run-1", 1);

        verify(evidenceService, never()).retrieve(any(), any(), any());
        verify(chatClient).chatWithinBudget(any(), any(), any());
        verify(runService).markSucceeded(eq("run-1"), any(VideoAgentResult.class), any());
    }

    @Test
    void rejectsModelReferencesThatWereNotProvided() throws InterruptedException {
        prepareRun();
        when(runService.completedStep(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(evidenceService.retrieve(any(), eq("query"), any())).thenReturn(bundle());
        when(chatClient.chatWithinBudget(any(), any(), any())).thenReturn(
                chat(planJson()), chat(draftJson("OCR:999"))
        );

        assertThatThrownBy(() -> service.process("run-1", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable evidence reference");

        verify(runService, never()).markSucceeded(any(), any(), any());
    }

    @Test
    void resumesSavedRetrievalAfterDraftingWaitsForQuota() throws InterruptedException {
        prepareRun();
        var checkpoints = new java.util.HashMap<String, String>();
        when(runService.completedStep(any(), any(), anyInt()))
                .thenAnswer(call -> Optional.ofNullable(checkpoints.get(call.getArgument(1))));
        doAnswer(call -> {
            checkpoints.put(call.getArgument(1), JSON.toJSONString(call.getArgument(4)));
            return null;
        }).when(runService).saveCompletedStep(any(), any(), anyInt(), any(), any());
        var expanded = new VideoAgentEvidenceService.EvidenceBundle(List.of(), List.of(
                bundle().evidence().getFirst(),
                new VideoAgentCitation("OCR:1", "OCR", 1L, 299_000, 299_000, "相邻 OCR 证据")));
        when(evidenceService.retrieve(any(), eq("query"), any())).thenReturn(expanded);
        when(chatClient.chatWithinBudget(any(), any(), any()))
                .thenReturn(chat(planJson()))
                .thenThrow(new com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException("busy", 60))
                .thenReturn(chat(draftJson("TRANSCRIPT:1")), chat(criticJson(true)));

        assertThatThrownBy(() -> service.process("run-1", 0))
                .isInstanceOf(com.example.server.service.ai.AiRateLimitService.AiRateLimitExceededException.class);
        service.process("run-1", 0);

        verify(runService).saveCompletedStep(eq("run-1"), eq("RETRIEVAL_1"), eq(0), eq("query"), eq(expanded));
        assertThat(checkpoints.get("RETRIEVAL_1")).contains("相邻 OCR 证据", "OCR:1");
        verify(evidenceService, times(1)).retrieve(any(), eq("query"), any());
        verify(runService).markSucceeded(eq("run-1"), any(VideoAgentResult.class), any());
    }

    @Test
    void criticKeepsFullCitedEvidenceAndDropsOptionalEvidenceBeforeGenerating() throws InterruptedException {
        prepareRun();
        String complete = "x".repeat(1000) + "结尾条件不能丢";
        cacheExecution(List.of(evidence(1, complete), evidence(2, "optional".repeat(3000))));
        var bounded = new VideoAgentWorkflowService(runService, indexService, evidenceService, chatClient,
                new AiPromptBudget(4000, 512));
        when(chatClient.chatWithinBudget(any(), any(), any())).thenAnswer(call -> {
            List<com.example.server.service.ai.impl.DeepSeekMessage> messages = call.getArgument(1);
            assertThat(messages.getFirst().content()).contains(complete).doesNotContain("optional");
            return chat(criticJson(true));
        });
        bounded.process("run-1", 0);
        verify(runService).markSucceeded(eq("run-1"), any(), any());
    }

    @Test
    void criticRejectsReferencesToOptionalEvidenceRemovedByTheBudget() throws InterruptedException {
        prepareRun();
        cacheExecution(List.of(evidence(1, "完整证据"), evidence(2, "optional".repeat(3000))));
        var bounded = new VideoAgentWorkflowService(runService, indexService, evidenceService, chatClient,
                new AiPromptBudget(4000, 512));
        when(chatClient.chatWithinBudget(any(), any(), any()))
                .thenReturn(chat(criticJson(true).replace("TRANSCRIPT:1", "TRANSCRIPT:2")));
        assertThatThrownBy(() -> bounded.process("run-1", 0)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable evidence reference");
        verify(runService, never()).markSucceeded(any(), any(), any());
    }

    @Test
    void failsBeforeAnyGenerationWhenPinnedEvidenceCannotFit() throws InterruptedException {
        prepareRun();
        cacheExecution(List.of(evidence(1, "pinned".repeat(3000))));
        var bounded = new VideoAgentWorkflowService(runService, indexService, evidenceService, chatClient,
                new AiPromptBudget(4000, 512));
        assertThatThrownBy(() -> bounded.process("run-1", 0))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        verify(chatClient, never()).chatWithinBudget(any(), any(), any());
        verify(runService, never()).reserveGenerationCall(any());
    }

    @Test
    void revisionPinsReferencesFromBothTheDraftAndCurrentResult() throws InterruptedException {
        prepareRun();
        String first = "a".repeat(900) + "草稿完整证据";
        String second = "b".repeat(900) + "当前结果完整证据";
        cacheExecution(List.of(evidence(1, first), evidence(2, second), evidence(3, "optional".repeat(3000))));
        when(runService.completedStep("run-1", "CRITIC", 0)).thenReturn(Optional.of(
                criticJson(false).replace("TRANSCRIPT:1", "TRANSCRIPT:2")));
        var bounded = new VideoAgentWorkflowService(runService, indexService, evidenceService, chatClient,
                new AiPromptBudget(5200, 512));
        when(chatClient.chatWithinBudget(any(), any(), any())).thenAnswer(call -> {
            List<com.example.server.service.ai.impl.DeepSeekMessage> messages = call.getArgument(1);
            assertThat(messages.getFirst().content()).contains(first, second).doesNotContain("optional");
            return chat(JSON.parseObject(criticJson(true)).getJSONObject("result").toJSONString());
        });
        bounded.process("run-1", 0);
        verify(runService).markSucceeded(eq("run-1"), any(), any());
    }

    private void cacheExecution(List<VideoAgentCitation> evidence) {
        var plan = new VideoAgentWorkflowService.Plan(List.of(
                new VideoAgentWorkflowService.Subtask("S1", "title", "query", "outcome")));
        var execution = new VideoAgentWorkflowService.ExecutionOutput(plan.subtasks().getFirst(),
                new VideoAgentWorkflowService.Draft("draft", List.of(
                        new VideoAgentWorkflowService.Claim("claim", List.of("TRANSCRIPT:1")))), evidence);
        when(runService.completedStep("run-1", "PLANNER", 0)).thenReturn(Optional.of(JSON.toJSONString(plan)));
        when(runService.completedStep("run-1", "EXECUTOR_1", 0)).thenReturn(Optional.of(JSON.toJSONString(execution)));
        when(runService.completedStep("run-1", "CRITIC", 0)).thenReturn(Optional.empty());
        when(runService.completedStep("run-1", "REVISION", 1)).thenReturn(Optional.empty());
    }

    private VideoAgentCitation evidence(long id, String text) {
        return new VideoAgentCitation("TRANSCRIPT:" + id, "TRANSCRIPT", id, 1000, 2000, text);
    }

    private void prepareRun() throws InterruptedException {
        VideoAgentRun run = new VideoAgentRun();
        run.setId("run-1");
        run.setMediaId(42L);
        run.setGoal("learn this");
        run.setContextVersion("t1-v1-p2");
        run.setGenerationCalls(3);
        run.setRetrievalCalls(1);
        when(runService.beginAttempt("run-1", 0)).thenReturn(true);
        when(runService.beginAttempt("run-1", 1)).thenReturn(true);
        when(runService.getRequired("run-1")).thenReturn(run);
        when(indexService.requireReady(42L)).thenReturn(index());
    }

    private VideoKnowledgeIndexService.IndexSnapshot index() {
        VideoContext context = new VideoContext(
                42L, "t1-v1-p2", "ALIYUN_ASR",
                new VideoContext.Modality("AVAILABLE", 1, 1, 1, null),
                new VideoContext.Modality("AVAILABLE", 1, 0, 0, null),
                List.of(), List.of()
        );
        return new VideoKnowledgeIndexService.IndexSnapshot(context, List.of(), "whole summary");
    }

    private VideoAgentEvidenceService.EvidenceBundle bundle() {
        return new VideoAgentEvidenceService.EvidenceBundle(
                List.of(),
                List.of(new VideoAgentCitation(
                        "TRANSCRIPT:1", "TRANSCRIPT", 1L, 1_000, 2_000, "evidence"
                ))
        );
    }

    private DeepSeekChatClient.ChatResult chat(String content) {
        return new DeepSeekChatClient.ChatResult(content, 10, 5);
    }

    private String planJson() {
        return """
                {"subtasks":[{"id":"S1","title":"title","query":"query","expectedOutcome":"outcome"}]}
                """;
    }

    private String draftJson(String ref) {
        return """
                {"title":"draft","claims":[{"text":"claim","evidenceRefs":["%s"]}]}
                """.formatted(ref);
    }

    private String criticJson(boolean passed) {
        return """
                {"passed":%s,"feedback":[],"result":{"title":"notes","coreConclusion":"core","coreEvidenceRefs":["TRANSCRIPT:1"],"knowledgePoints":[{"title":"point","explanation":"explain","evidenceRefs":["TRANSCRIPT:1"]}],"quiz":[{"question":"q1","answer":"a1","evidenceRefs":["TRANSCRIPT:1"]},{"question":"q2","answer":"a2","evidenceRefs":["TRANSCRIPT:1"]},{"question":"q3","answer":"a3","evidenceRefs":["TRANSCRIPT:1"]}],"gaps":[]}}
                """.formatted(passed);
    }
}

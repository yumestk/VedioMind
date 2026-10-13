package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.dto.VideoAgentResult;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoAgentWorkflowServiceTest {

    private final VideoAgentRunService runService = mock(VideoAgentRunService.class);
    private final VideoKnowledgeIndexService indexService = mock(VideoKnowledgeIndexService.class);
    private final VideoAgentEvidenceService evidenceService = mock(VideoAgentEvidenceService.class);
    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final VideoAgentWorkflowService service = new VideoAgentWorkflowService(
            runService, indexService, evidenceService, chatClient, redissonClient
    );

    @Test
    void runsPlannerExecutorCriticAndPersistsValidatedResult() throws InterruptedException {
        prepareRun();
        when(runService.completedStep(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(evidenceService.retrieve(any(), eq("query"))).thenReturn(bundle());
        when(chatClient.chatWithUsage(any(), any())).thenReturn(
                chat(planJson()), chat(draftJson("TRANSCRIPT:1")), chat(criticJson(true))
        );

        service.process("run-1", 0);

        verify(evidenceService).retrieve(any(), eq("query"));
        verify(runService).markSucceeded(eq("run-1"), any(VideoAgentResult.class), any());
        verify(lock).unlock();
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
        when(chatClient.chatWithUsage(any(), any())).thenReturn(chat(criticJson(true)));

        service.process("run-1", 1);

        verify(evidenceService, never()).retrieve(any(), any());
        verify(chatClient).chatWithUsage(any(), any());
        verify(runService).markSucceeded(eq("run-1"), any(VideoAgentResult.class), any());
    }

    @Test
    void rejectsModelReferencesThatWereNotProvided() throws InterruptedException {
        prepareRun();
        when(runService.completedStep(any(), any(), anyInt())).thenReturn(Optional.empty());
        when(evidenceService.retrieve(any(), eq("query"))).thenReturn(bundle());
        when(chatClient.chatWithUsage(any(), any())).thenReturn(
                chat(planJson()), chat(draftJson("OCR:999"))
        );

        assertThatThrownBy(() -> service.process("run-1", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable evidence reference");

        verify(runService, never()).markSucceeded(any(), any(), any());
    }

    private void prepareRun() throws InterruptedException {
        VideoAgentRun run = new VideoAgentRun();
        run.setId("run-1");
        run.setMediaId(42L);
        run.setGoal("learn this");
        run.setContextVersion("t1-v1-p2");
        run.setGenerationCalls(3);
        run.setRetrievalCalls(1);
        when(redissonClient.getLock("lock:video-agent-run:run-1")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
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

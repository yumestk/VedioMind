package com.example.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.dto.PreparedStudyTask;
import com.example.server.entity.VideoAgentRun;
import com.example.server.service.ai.*;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StudyDecisionBudgetTest {
    private JSONObject budget(long remaining, int round) {
        var task = PreparedStudyTask.create("request", 1L, 1L, 1L, 42L, 1L,
                new PreparedStudyTask.Scope("MATERIALS", null, List.of(42L)), List.of(),
                "笔记", "笔记", List.of("NOTE"), "COURSE_ONLY",
                PreparedStudyTask.Budget.boundedStudy(55000, 8192, 2));
        var run = new VideoAgentRun();
        run.setAccountedTokens(96000 - remaining);
        run.setGenerationCalls(3);
        run.setRetrievalCalls(3);
        var runs = mock(VideoAgentRunService.class);
        when(runs.getRequired("run")).thenReturn(run);
        when(runs.requestDeadline("run")).thenReturn(Instant.now().plusSeconds(110));
        var estimator = mock(AiPromptBudget.class);
        when(estimator.estimateRequest(any())).thenReturn(15000L);
        var runtime = new StudyAgentRuntime(runs, null, null, null, estimator);
        try (var ignored = StudyExecutionContext.open(task, "run", 1)) {
            return ReflectionTestUtils.invokeMethod(runtime, "decisionBudget", "run", round, 6,
                    Set.of("42:OCR:1:v1"), Set.of(), false, List.of());
        }
    }

    @Test void reservesReadAndSaveInsteadOfPrematurelyFinalizingWithoutEvidence() {
        // 60k cannot cover the old three 15k+4096 decisions, but covers bounded read+save.
        var state = budget(60000, 3);
        assertThat(state.getString("phase")).isEqualTo("READ");
        assertThat(state.getIntValue("outputTokens")).isEqualTo(1024);
        assertThat(state.getLongValue("accountedTokensRemaining")).isEqualTo(60000);
    }

    @Test void cannotBorrowBeyondHardTokenOrDecisionLimits() {
        assertThatThrownBy(() -> budget(40000, 3)).isInstanceOfSatisfying(GovernanceException.class,
                e -> assertThat(e.code()).isEqualTo("TASK_BUDGET_EXHAUSTED"));
        assertThatThrownBy(() -> budget(60000, 4)).isInstanceOfSatisfying(GovernanceException.class,
                e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_EVIDENCE"));
    }
}

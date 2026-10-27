package com.example.server.service;

import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.dto.VideoQuestionResponse;
import com.example.server.service.ai.impl.DeepSeekChatClient;
import com.example.server.service.ai.impl.DeepSeekMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoQuestionServiceTest {

    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final VideoQuestionService service = new VideoQuestionService(chatClient, new AiPromptBudget(32000, 8192));
    private final List<VideoEvidenceContextService.Candidate> segments = List.of(
            candidate(11L, "第一段字幕", true, 0),
            candidate(12L, "第二段字幕", true, 0)
    );

    @Test
    void mapsValidatedSegmentIdsToServerOwnedCitations() {
        when(chatClient.chatWithinBudget(anyString(), anyList(), any())).thenReturn(chat("""
                ```json
                {"answer":"这是回答","segmentIds":[12,11,12]}
                ```
                """));

        List<DeepSeekMessage> history = List.of(
                new DeepSeekMessage("user", "前一个问题"),
                new DeepSeekMessage("assistant", "前一个回答")
        );
        VideoQuestionResponse response = service.answer(
                "视频讲了什么？", segments, history, "知识点 1：事务消息"
        );

        assertThat(response.answer()).isEqualTo("这是回答");
        assertThat(response.citations()).extracting("segmentId").containsExactly(12L, 11L);
        assertThat(response.citations().getFirst().startMs()).isEqualTo(3_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeepSeekMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(chatClient).chatWithinBudget(anyString(), messages.capture(), any());
        assertThat(messages.getValue()).hasSize(3);
        assertThat(messages.getValue().subList(0, 2)).isEqualTo(history);
        assertThat(messages.getValue().getLast().content()).contains(
                "深度学习任务结果（仅用于理解问题，不是事实证据）",
                "知识点 1：事务消息",
                "\"segmentId\":11",
                "\"text\":\"第一段字幕\"",
                "\"segmentId\":12"
        );
    }

    @Test
    void rejectsCitationsThatAreNotInTheStoredTranscript() {
        when(chatClient.chatWithinBudget(anyString(), anyList(), any()))
                .thenReturn(chat("{\"answer\":\"猜测\",\"segmentIds\":[999]}"));

        assertThatThrownBy(() -> service.answer("问题", segments, List.of(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid transcript citation");
    }

    @Test
    void returnsTheCanonicalNoAnswerWhenTheModelHasNoCitation() {
        when(chatClient.chatWithinBudget(anyString(), anyList(), any()))
                .thenReturn(chat("{\"answer\":\"随便说点什么\",\"segmentIds\":[]}"));

        VideoQuestionResponse response = service.answer("字幕中没有的问题", segments, List.of(), "");

        assertThat(response.answer()).isEqualTo("视频字幕中没有足够信息回答这个问题。");
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void returnsTheCanonicalNoAnswerWithoutCallingTheModelWhenRetrievalIsEmpty() {
        VideoQuestionResponse response = service.answer("没有召回的问题", List.of(), List.of(), "");

        assertThat(response.answer()).isEqualTo("视频字幕中没有足够信息回答这个问题。");
        assertThat(response.citations()).isEmpty();
        verify(chatClient, never()).chatWithinBudget(anyString(), anyList(), any());
    }

    @Test
    void dropsNeighborsThenHistoryAndAgentContextBeforeCoreEvidence() {
        AiPromptBudget budget = new AiPromptBudget(4000, 512);
        var bounded = new VideoQuestionService(chatClient, budget);
        when(chatClient.chatWithinBudget(anyString(), anyList(), any())).thenAnswer(call -> {
            assertThat(budget.fits(call.getArgument(0), call.getArgument(1))).isTrue();
            List<DeepSeekMessage> messages = call.getArgument(1);
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().content()).contains("完整核心证据")
                    .doesNotContain("邻居", "旧历史", "任务结果");
            return chat("{\"answer\":\"回答\",\"segmentIds\":[11]}");
        });
        bounded.answer("问题", List.of(candidate(11L, "完整核心证据", true, 0),
                        candidate(12L, "邻居".repeat(3000), false, 20_000)),
                List.of(new DeepSeekMessage("user", "旧历史".repeat(2000)),
                        new DeepSeekMessage("assistant", "旧回答")), "任务结果".repeat(2000));
    }

    @Test
    void skipsAnOversizedWholeCoreSegmentAndKeepsOtherHitsWithoutTruncation() {
        var bounded = new VideoQuestionService(chatClient, new AiPromptBudget(4000, 512));
        when(chatClient.chatWithinBudget(anyString(), anyList(), any())).thenAnswer(call -> {
            List<DeepSeekMessage> messages = call.getArgument(1);
            assertThat(messages.getLast().content()).contains("短的完整证据").doesNotContain("超长");
            return chat("{\"answer\":\"回答\",\"segmentIds\":[12]}");
        });
        assertThat(bounded.answer("问题", List.of(candidate(11L, "超长".repeat(3000), true, 0),
                candidate(12L, "短的完整证据", true, 0)), List.of(), "").citations())
                .extracting("segmentId").containsExactly(12L);
    }

    @Test
    void rejectsAReferenceToEvidenceRemovedByTheBudget() {
        var bounded = new VideoQuestionService(chatClient, new AiPromptBudget(4000, 512));
        when(chatClient.chatWithinBudget(anyString(), anyList(), any()))
                .thenReturn(chat("{\"answer\":\"回答\",\"segmentIds\":[12]}"));
        assertThatThrownBy(() -> bounded.answer("问题", List.of(candidate(11L, "核心", true, 0),
                candidate(12L, "邻居".repeat(3000), false, 30_000)), List.of(), ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid transcript citation");
    }

    @Test
    void failsBeforeGenerationWhenNoWholeEvidenceFits() {
        var bounded = new VideoQuestionService(chatClient, new AiPromptBudget(4000, 512));
        assertThatThrownBy(() -> bounded.answer("问题", List.of(candidate(11L, "巨长".repeat(3000), true, 0)),
                List.of(), "")).isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        verify(chatClient, never()).chatWithinBudget(any(), any(), any());
    }

    private VideoEvidenceContextService.Candidate candidate(long id, String text, boolean core, long distance) {
        return new VideoEvidenceContextService.Candidate(new VideoAgentCitation("TRANSCRIPT:" + id,
                "TRANSCRIPT", id, id == 11 ? 1000 : 3000, id == 11 ? 3000 : 6000, text), core, distance);
    }

    private DeepSeekChatClient.ChatResult chat(String content) {
        return new DeepSeekChatClient.ChatResult(content, 10, 5);
    }
}

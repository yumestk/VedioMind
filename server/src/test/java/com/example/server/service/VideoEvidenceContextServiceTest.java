package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.context.VideoContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VideoEvidenceContextServiceTest {
    private final VideoEvidenceContextService service = new VideoEvidenceContextService(3, 30);

    @Test
    void restoresTheConditionAcrossTheFiveMinuteBoundaryWithoutChangingTheHit() {
        var context = context(List.of(
                transcript(1, 298_000, "只有消息已经提交，"),
                transcript(2, 302_000, "消费者才能执行后续业务。")), List.of());
        var hit = hit(300_000, 600_000, List.of(2L), List.of());
        var expanded = service.expand(context, List.of(hit), false);

        assertThat(hit.transcriptSegmentIds()).containsExactly(2L); // baseline misses the condition
        assertThat(expanded).extracting(c -> c.evidence().ref()).containsExactly("TRANSCRIPT:2", "TRANSCRIPT:1");
        assertThat(expanded.getLast().core()).isFalse();
        assertThat(expanded.getLast().evidence().text()).isEqualTo("只有消息已经提交，");
        assertThat(expanded.getLast().evidence().startMs()).isEqualTo(298_000);
        assertThat(service.select(expanded, 5000, 80, 20)).hasSize(2);
    }

    @Test
    void supplementsTheConclusionAfterTheBoundaryAndKeepsOcrOutOfQuickAnswers() {
        var context = context(List.of(transcript(1, 298_000, "如果提交成功，"),
                        transcript(2, 302_000, "才可继续消费。")),
                List.of(visual(1, 301_000, "SUCCEEDED", "只在提交后执行")));
        var hit = hit(0, 300_000, List.of(1L), List.of());
        assertThat(service.expand(context, List.of(hit), false)).extracting(c -> c.evidence().ref())
                .containsExactly("TRANSCRIPT:1", "TRANSCRIPT:2");
        assertThat(service.expand(context, List.of(hit), true)).extracting(c -> c.evidence().ref())
                .containsExactly("TRANSCRIPT:1", "OCR:1", "TRANSCRIPT:2");
    }

    @ParameterizedTest
    @ValueSource(longs = {250_000, 300_000})
    void mergesOverlappingOrAdjacentHitsAndExpandsOnlyTheirOuterEdges(long secondStart) {
        var context = context(List.of(transcript(1, 1000, "首块"), transcript(2, 310_000, "次块"),
                transcript(3, 249_000, "内部未选取句子"), transcript(4, 601_000, "外侧相邻句")), List.of());
        var expanded = service.expand(context, List.of(hit(0, 300_000, List.of(1L), List.of()),
                hit(secondStart, 600_000, List.of(2L), List.of())), false);
        assertThat(expanded).extracting(c -> c.evidence().ref())
                .containsExactly("TRANSCRIPT:1", "TRANSCRIPT:2", "TRANSCRIPT:4");
    }

    @Test
    void enforcesTimeAndCountBoundsAndHandlesVideoEndsAndFailedOcr() {
        var context = context(List.of(transcript(1, 302_000, "核心"),
                        transcript(2, 290_000, "邻居一"), transcript(3, 280_000, "邻居二"),
                        transcript(4, 275_000, "邻居三"), transcript(5, 271_000, "第四条舍弃"),
                        transcript(6, 260_000, "静默间隔外舍弃")),
                List.of(visual(1, 299_000, "FAILED", "不可用"), visual(2, 299_000, "SUCCEEDED", " ")));
        assertThat(service.expand(context, List.of(hit(300_000, 600_000, List.of(1L), List.of())), true))
                .extracting(c -> c.evidence().ref())
                .containsExactly("TRANSCRIPT:1", "TRANSCRIPT:2", "TRANSCRIPT:3", "TRANSCRIPT:4");
        assertThat(service.expand(context, List.of(hit(0, 300_000, List.of(6L), List.of())), true))
                .extracting(c -> c.evidence().ref()).containsExactly("TRANSCRIPT:6", "TRANSCRIPT:1");
        assertThat(service.expand(context, List.of(), true)).isEmpty();
        assertThat(new VideoEvidenceContextService(0, 30).expand(context,
                List.of(hit(300_000, 600_000, List.of(1L), List.of())), true)).hasSize(1);
    }

    @Test
    void deduplicatesByTypedIdAndRoundRobinsCoreEvidenceBeforeSupplements() {
        var context = context(List.of(transcript(1, 1000, "一"), transcript(2, 2000, "二"),
                        transcript(3, 302_000, "三"), transcript(4, 603_000, "邻居")),
                List.of(visual(1, 1000, "SUCCEEDED", "OCR 与字幕同号")));
        var expanded = service.expand(context, List.of(
                hit(0, 300_000, List.of(1L, 2L, 1L, 999L), List.of(1L)),
                hit(300_000, 600_000, List.of(3L), List.of())), true);
        assertThat(expanded).extracting(c -> c.evidence().ref())
                .containsExactly("OCR:1", "TRANSCRIPT:3", "TRANSCRIPT:1", "TRANSCRIPT:2", "TRANSCRIPT:4");
        assertThat(service.select(expanded, 5000, 1, 1)).extracting(VideoAgentCitation::ref)
                .containsExactly("OCR:1", "TRANSCRIPT:3");
        assertThat(service.select(expanded, 1, 80, 20)).isEmpty();
    }

    @Test
    void skipsOversizedWholeEvidenceInsteadOfTruncatingOrBlockingOtherHits() {
        var context = context(List.of(transcript(1, 1000, "长".repeat(3000)), transcript(2, 302_000, "完整短证据")), List.of());
        var expanded = service.expand(context, List.of(hit(0, 300_000, List.of(1L), List.of()),
                hit(300_000, 600_000, List.of(2L), List.of())), false);
        var selected = service.select(expanded, 1000, 80, 20);
        assertThat(selected).extracting(VideoAgentCitation::text).containsExactly("完整短证据");
        assertThat(AiPromptBudget.estimateText(JSON.toJSONString(selected))).isLessThanOrEqualTo(1000);
    }

    @Test
    void agentRetrievalExpandsTheSameSnapshotAfterASingleEmbeddingAndChecksTheBudget() {
        var context = context(List.of(transcript(1, 298_000, "边界条件"), transcript(2, 302_000, "结论")),
                List.of(visual(1, 299_000, "SUCCEEDED", "补充 OCR")));
        var index = new VideoKnowledgeIndexService.IndexSnapshot(context, List.of(), "摘要");
        var retriever = mock(VideoKnowledgeRetriever.class);
        Runnable reserve = mock(Runnable.class);
        when(retriever.searchMultimodal(eq(index), eq("查询"), eq(reserve)))
                .thenReturn(List.of(hit(300_000, 600_000, List.of(2L), List.of())));
        var evidenceService = new VideoAgentEvidenceService(retriever, service, new AiPromptBudget(32000, 8192));
        assertThat(evidenceService.retrieve(index, "查询", reserve).evidence()).extracting(VideoAgentCitation::ref)
                .containsExactly("TRANSCRIPT:2", "OCR:1", "TRANSCRIPT:1");
        verify(retriever).searchMultimodal(index, "查询", reserve);
        verifyNoMoreInteractions(retriever);

        var huge = context(List.of(transcript(2, 302_000, "长".repeat(10000))), List.of());
        var hugeIndex = new VideoKnowledgeIndexService.IndexSnapshot(huge, List.of(), "摘要");
        when(retriever.searchMultimodal(eq(hugeIndex), any(), any()))
                .thenReturn(List.of(hit(300_000, 600_000, List.of(2L), List.of())));
        assertThatThrownBy(() -> evidenceService.retrieve(hugeIndex, "查询", reserve))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
    }

    private VideoContext context(List<VideoContext.TranscriptEvidence> transcripts, List<VideoContext.VisualEvidence> visuals) {
        return new VideoContext(42L, "t1-v1", "ASR", null, null, transcripts, visuals);
    }
    private VideoContext.TranscriptEvidence transcript(long id, long start, String text) {
        return new VideoContext.TranscriptEvidence(id, start, start + 1000, text);
    }
    private VideoContext.VisualEvidence visual(long id, long time, String status, String text) {
        return new VideoContext.VisualEvidence(id, time, "frame", status, 90.0, text);
    }
    private VideoKnowledgeRetriever.KnowledgeSearchHit hit(long start, long end, List<Long> transcripts, List<Long> visuals) {
        return new VideoKnowledgeRetriever.KnowledgeSearchHit((int) (start / 300_000), start, end, "摘要", List.of(), 0.9, transcripts, visuals);
    }
}

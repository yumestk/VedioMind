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
    void promotesRelevantEvidenceAtTheEndOfAChunkBeforeBudgetPacking() {
        var transcripts = new java.util.ArrayList<VideoContext.TranscriptEvidence>();
        for (int i = 0; i < 80; i++) transcripts.add(transcript(i + 1, i * 3000, "前面讨论其他事情"));
        transcripts.add(transcript(81, 280_000, "AI对就业的冲击集中在初级岗位"));
        transcripts.add(transcript(82, 282_000, "软件工程师可以结合客户沟通与技术开发"));
        var candidates = service.expand(context(transcripts, List.of()),
                List.of(hit(0, 300_000, transcripts.stream().map(VideoContext.TranscriptEvidence::id).toList(), List.of())), false);
        var ranked = service.rankForQuestion("AI对就业的冲击体现在哪", candidates);
        assertThat(ranked.getFirst().evidence().sourceId()).isIn(81L, 82L);
        assertThat(service.select(ranked, 900, 4, 0)).extracting(VideoAgentCitation::sourceId).contains(81L);
    }

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
                .containsExactly("TRANSCRIPT:1", "TRANSCRIPT:2", "OCR:1");
        verify(retriever).searchMultimodal(index, "查询", reserve);
        verifyNoMoreInteractions(retriever);

        var huge = context(List.of(transcript(2, 302_000, "长".repeat(10000))), List.of());
        var hugeIndex = new VideoKnowledgeIndexService.IndexSnapshot(huge, List.of(), "摘要");
        when(retriever.searchMultimodal(eq(hugeIndex), any(), any()))
                .thenReturn(List.of(hit(300_000, 600_000, List.of(2L), List.of())));
        assertThatThrownBy(() -> evidenceService.retrieve(hugeIndex, "查询", reserve))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
    }

    @Test
    void learningRetrievalKeepsEvidenceFromEveryHitBeforeReusingSharedSpace() {
        var transcripts = new java.util.ArrayList<VideoContext.TranscriptEvidence>();
        var firstIds = new java.util.ArrayList<Long>();
        for (long i=1;i<=8;i++) {
            transcripts.add(transcript(i,i*35_000,"English paragraph ".repeat(100)));
            firstIds.add(i);
        }
        transcripts.add(transcript(99,310_000,"Complete qualifying condition, not a proven theorem."));
        var index = new VideoKnowledgeIndexService.IndexSnapshot(context(transcripts,List.of()),List.of(),"navigation");
        var retriever = mock(VideoKnowledgeRetriever.class);
        when(retriever.searchMultimodal(eq(index),anyString(),any()))
                .thenReturn(List.of(hit(0,300_000,firstIds,List.of()),hit(300_000,600_000,List.of(99L),List.of())));
        var selected = new VideoAgentEvidenceService(retriever,service,new AiPromptBudget(32000,8192))
                .retrieve(index,"中文学习主题",()->{}).evidence();
        assertThat(selected).extracting(VideoAgentCitation::ref).contains("TRANSCRIPT:1","TRANSCRIPT:99");
        assertThat(selected.stream().filter(v->v.ref().equals("TRANSCRIPT:99")).findFirst().orElseThrow().text())
                .isEqualTo("Complete qualifying condition, not a proven theorem.");
        assertThat(AiPromptBudget.estimateText(VideoEvidenceContextService.formatEvidence(selected)))
                .isLessThanOrEqualTo(23296-2048);
    }

    @Test
    void aCompleteShortSubtitleArgumentIsNotDroppedByAnArbitraryItemCount() {
        var paragraphs = new java.util.ArrayList<List<VideoAgentCitation>>();
        for(int i=0;i<120;i++) paragraphs.add(List.of(new VideoAgentCitation("TRANSCRIPT:"+i,"TRANSCRIPT",(long)i,i*1000L,i*1000L+1000,
                i==119 ? "最后一句给出了结论的适用条件" : "同一论证中的短字幕")));
        var selected = service.allocate(List.of(paragraphs),54000,List.of(values->true)).getFirst();
        assertThat(selected).hasSize(120).extracting(VideoAgentCitation::ref).contains("TRANSCRIPT:119");
        assertThat(AiPromptBudget.estimateText(VideoEvidenceContextService.formatEvidence(selected))).isLessThanOrEqualTo(54000);
    }

    @Test
    void sharedBudgetReusesDuplicateSpaceAndPacksWholeWindowsForEveryTask() {
        var a = new VideoAgentCitation("TRANSCRIPT:1", "TRANSCRIPT", 1L, 0, 1, "共同条件");
        var b = new VideoAgentCitation("TRANSCRIPT:2", "TRANSCRIPT", 2L, 1, 2, "共同结论");
        var c = new VideoAgentCitation("TRANSCRIPT:3", "TRANSCRIPT", 3L, 2, 3, "另一主题");
        var window = List.of(a, b);
        long capacity = AiPromptBudget.estimateText(VideoEvidenceContextService.formatEvidence(List.of(a, b, c)));
        var packed = service.allocate(List.of(List.of(window, List.of(c)), List.of(window)), capacity,
                List.of(values -> true, values -> true));
        assertThat(packed.get(0)).containsExactlyInAnyOrder(a, b, c);
        assertThat(packed.get(1)).containsExactly(a, b);
        var tight = service.allocate(List.of(List.of(window, List.of(c))),
                AiPromptBudget.estimateText(VideoEvidenceContextService.formatEvidence(List.of(c))), List.of(values -> true));
        assertThat(tight.getFirst()).containsExactly(c);
        assertThat(service.allocate(List.of(List.of(window)), capacity, List.of(values -> false)).getFirst()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void allocatesOneTwoOrThreeDisjointTasksWithinTheSameUnionLimit(int tasks) {
        var windows = new java.util.ArrayList<List<List<VideoAgentCitation>>>();
        var checks = new java.util.ArrayList<java.util.function.Predicate<List<VideoAgentCitation>>>();
        var all = new java.util.ArrayList<VideoAgentCitation>();
        for (int i = 0; i < tasks; i++) {
            var citation = new VideoAgentCitation("TRANSCRIPT:" + i, "TRANSCRIPT", (long) i, i, i + 1, "主题" + i);
            windows.add(List.of(List.of(citation))); checks.add(values -> true); all.add(citation);
        }
        long limit = AiPromptBudget.estimateText(VideoEvidenceContextService.formatEvidence(all));
        var selected = service.allocate(windows, limit, checks);
        assertThat(selected).hasSize(tasks).allSatisfy(values -> assertThat(values).hasSize(1));
        assertThat(selected.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(all);
    }

    @Test
    void ocrCannotDisplaceSubtitleNeighborsOrOverwriteASameNumberedSubtitleScore() {
        var context = context(List.of(transcript(1, 1000, "如果提交成功"), transcript(2, 2000, "它才能继续消费")),
                List.of(visual(1, 1100, "SUCCEEDED", "无关画面")));
        var candidates = service.expand(context, List.of(hit(0, 300_000, List.of(1L, 2L), List.of(1L))), true);
        var ranked = service.rankForQuestion("提交成功才能继续消费", candidates);
        assertThat(ranked.getFirst().evidence().type()).isEqualTo("TRANSCRIPT");
        assertThat(service.windows("提交成功才能继续消费", candidates).getFirst())
                .extracting(VideoAgentCitation::ref).containsExactly("TRANSCRIPT:1", "TRANSCRIPT:2");
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

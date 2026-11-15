package com.example.server.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.server.dto.VideoAgentCitation;
import com.example.server.service.ai.StudyExecutionContext.GovernanceException;
import java.util.*;
import org.junit.jupiter.api.Test;

class StudyEvidenceServiceTest {
    private final StudyEvidenceService service = spy(new StudyEvidenceService(null, null, null, null, null));

    private VideoEvidenceContextService.Candidate original(long material, long id, String type, long time, String text) {
        return new VideoEvidenceContextService.Candidate(new VideoAgentCitation(
                material + ":" + type + ":" + id + ":v1", type, id, time, time + 1000, text, material, "v1"), true, 0);
    }

    @Test void readsQualifyingContextBeyondThirtySecondsWithoutCrossingMaterialOrDuplicatingOverlaps() {
        var condition = original(43, 1, "TRANSCRIPT", 10_000, "前提是双向循环链表，已知首尾节点");
        var anchor = original(43, 2, "TRANSCRIPT", 81_000, "因此这里只需修改指针");
        var ocr = original(43, 2, "OCR", 70_000, "实现结构");
        var later = original(43, 3, "TRANSCRIPT", 100_000, "操作结果");
        var foreign = original(44, 1, "TRANSCRIPT", 10_000, "其他材料");
        var outside = original(43, 4, "TRANSCRIPT", 200_000, "时间范围外");
        doReturn(List.of(condition, anchor, ocr, later, foreign, outside)).when(service).full(null);
        assertThat(service.read(null, List.of(anchor.evidence().ref(), later.evidence().ref())).evidence())
                .containsExactly(condition.evidence(), ocr.evidence(), anchor.evidence(), later.evidence());
        assertThatThrownBy(() -> service.read(null, List.of("43:TRANSCRIPT:2:old")))
                .isInstanceOf(GovernanceException.class).hasMessageContaining("变化");
    }

    @Test void capsNeighborCountsAndRejectsOversizedWholeWindows() {
        var values = new ArrayList<VideoEvidenceContextService.Candidate>();
        for (int i = 0; i < 20; i++) values.add(original(42, i, "TRANSCRIPT", i * 1000, "原文"));
        doReturn(values).when(service).full(null);
        assertThat(service.read(null, List.of(values.get(10).evidence().ref())).evidence())
                .hasSize(13).extracting(VideoAgentCitation::sourceId)
                .containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L);
        values.set(9, original(42, 9, "TRANSCRIPT", 9000, "长".repeat(5000)));
        assertThatThrownBy(() -> service.read(null, List.of(values.get(10).evidence().ref())))
                .isInstanceOf(GovernanceException.class).hasMessageContaining("窗口超过14k");
    }
    @Test void packsWholeWindowsInRequestedOrderAndReportsDeferredOnes() {
        var a = original(42, 1, "OCR", 0, "甲".repeat(2500));
        var condition = original(42, 2, "TRANSCRIPT", 1000, "仅在已知节点时成立");
        var b = original(43, 3, "OCR", 0, "乙".repeat(2500));
        doReturn(List.of(a, condition, b)).when(service).full(null);
        var window = service.read(null, List.of(a.evidence().ref(), b.evidence().ref()));
        assertThat(window.evidence()).containsExactly(a.evidence(), condition.evidence());
        assertThat(window.deferredWindowAnchors()).containsExactly(b.evidence().ref());
        var reversed = service.read(null, List.of(b.evidence().ref(), a.evidence().ref()));
        assertThat(reversed.evidence()).containsExactly(b.evidence());
        assertThat(reversed.deferredWindowAnchors()).containsExactly(a.evidence().ref());
    }

    @Test void oversizedFirstWindowDoesNotBlockALaterReadableWindow() {
        var huge = original(42, 1, "OCR", 0, "长".repeat(5000));
        var shortOne = original(43, 2, "TRANSCRIPT", 0, "短证据");
        doReturn(List.of(huge, shortOne)).when(service).full(null);
        var window = service.read(null, List.of(huge.evidence().ref(), shortOne.evidence().ref()));
        assertThat(window.evidence()).containsExactly(shortOne.evidence());
        assertThat(window.deferredWindowAnchors()).containsExactly(huge.evidence().ref());
        assertThatThrownBy(() -> service.read(null, List.of(huge.evidence().ref())))
                .isInstanceOfSatisfying(GovernanceException.class,
                        e -> assertThat(e.code()).isEqualTo("EVIDENCE_WINDOW_TOO_LARGE"));
    }

}

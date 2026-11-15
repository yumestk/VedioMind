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
        assertThat(service.read(null, List.of(anchor.evidence().ref(), later.evidence().ref())))
                .containsExactly(condition.evidence(), ocr.evidence(), anchor.evidence(), later.evidence());
        assertThatThrownBy(() -> service.read(null, List.of("43:TRANSCRIPT:2:old")))
                .isInstanceOf(GovernanceException.class).hasMessageContaining("变化");
    }

    @Test void capsNeighborCountsAndRejectsOversizedWholeWindows() {
        var values = new ArrayList<VideoEvidenceContextService.Candidate>();
        for (int i = 0; i < 20; i++) values.add(original(42, i, "TRANSCRIPT", i * 1000, "原文"));
        doReturn(values).when(service).full(null);
        assertThat(service.read(null, List.of(values.get(10).evidence().ref())))
                .hasSize(13).extracting(VideoAgentCitation::sourceId)
                .containsExactly(4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L);
        values.set(9, original(42, 9, "TRANSCRIPT", 9000, "长".repeat(5000)));
        assertThatThrownBy(() -> service.read(null, List.of(values.get(10).evidence().ref())))
                .isInstanceOf(GovernanceException.class).hasMessageContaining("窗口超过14k");
    }
}

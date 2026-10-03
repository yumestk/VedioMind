package com.example.server.service;

import com.example.server.entity.TranscriptSegment;
import com.example.server.mapper.TranscriptSegmentMapper;
import com.example.server.service.ai.TranscriptSegmentDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TranscriptServiceTest {

    private final TranscriptService transcriptService = new TranscriptService(mock(TranscriptSegmentMapper.class));

    @Test
    void joinsPersistedSegmentsIntoTheSummaryInput() {
        TranscriptSegment first = segment("第一句");
        TranscriptSegment second = segment("第二句");

        assertThat(transcriptService.joinText(List.of(first, second)))
                .isEqualTo("第一句\n第二句");
    }

    @Test
    void rejectsReplacingATranscriptWithNoSegments() {
        assertThatThrownBy(() -> transcriptService.replace(42L, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    private TranscriptSegment segment(String text) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setText(text);
        return segment;
    }
}

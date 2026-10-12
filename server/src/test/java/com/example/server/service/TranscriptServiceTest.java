package com.example.server.service;

import com.example.server.entity.TranscriptSegment;
import com.example.server.mapper.TranscriptSegmentMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.ai.TranscriptSegmentDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

class TranscriptServiceTest {

    private final TranscriptSegmentMapper segmentMapper = mock(TranscriptSegmentMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final TranscriptService transcriptService = new TranscriptService(segmentMapper, mediaFileMapper);

    @Test
    void joinsPersistedSegmentsIntoTheSummaryInput() {
        TranscriptSegment first = segment("第一句");
        TranscriptSegment second = segment("第二句");

        assertThat(transcriptService.joinText(List.of(first, second)))
                .isEqualTo("第一句\n第二句");
    }

    @Test
    void rejectsReplacingATranscriptWithNoSegments() {
        assertThatThrownBy(() -> transcriptService.replace(42L, List.of(), "ALIYUN_ASR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void advancesTheTranscriptRevisionAfterPersistingSegments() {
        transcriptService.replace(42L, List.of(
                new TranscriptSegmentDraft(0, 1_000, "第一句")
        ), "ALIYUN_ASR");

        verify(segmentMapper).insert(any(TranscriptSegment.class));
        verify(mediaFileMapper).update(isNull(), any());
    }

    private TranscriptSegment segment(String text) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setText(text);
        return segment;
    }
}

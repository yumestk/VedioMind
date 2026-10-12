package com.example.server.service.context;

import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaVisualEvidence;
import com.example.server.entity.TranscriptSegment;
import com.example.server.entity.VideoKeyframe;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.service.TranscriptService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoContextServiceTest {

    private final MediaFileMapper mediaMapper = mock(MediaFileMapper.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final MediaVisualEvidenceMapper evidenceMapper = mock(MediaVisualEvidenceMapper.class);
    private final VideoKeyframeMapper keyframeMapper = mock(VideoKeyframeMapper.class);
    private final VideoContextService service = new VideoContextService(
            mediaMapper, transcriptService, evidenceMapper, keyframeMapper
    );

    @Test
    void assemblesVersionedTranscriptAndUsableOcrEvidence() {
        MediaFile media = media("LOCAL_UPLOAD");
        media.setTranscriptRevision(2);
        when(mediaMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(List.of(segment()));

        MediaVisualEvidence manifest = new MediaVisualEvidence();
        manifest.setStatus("PARTIAL");
        manifest.setProcessorVersion(2);
        manifest.setRevision(3);
        manifest.setFrameCount(2);
        manifest.setUsableOcrCount(1);
        manifest.setFailedOcrCount(1);
        when(evidenceMapper.selectById(42L)).thenReturn(manifest);
        when(keyframeMapper.selectList(any())).thenReturn(List.of(
                frame(1L, "SUCCEEDED", "课程标题"),
                frame(2L, "LOW_CONFIDENCE", "不应暴露")
        ));

        VideoContext context = service.build(42L);

        assertThat(context.version()).isEqualTo("t2-v3-p2");
        assertThat(context.transcript().state()).isEqualTo("AVAILABLE");
        assertThat(context.visual().state()).isEqualTo("PARTIAL");
        assertThat(context.visualEvidence()).hasSize(2);
        assertThat(context.visualEvidence().get(0).ocrText()).isEqualTo("课程标题");
        assertThat(context.visualEvidence().get(1).ocrText()).isNull();
    }

    @Test
    void marksExternalVideoVisualEvidenceAsNotApplicable() {
        MediaFile media = media("EXTERNAL_URL");
        media.setTranscriptRevision(1);
        when(mediaMapper.selectById(42L)).thenReturn(media);
        when(transcriptService.listByMediaId(42L)).thenReturn(List.of(segment()));

        VideoContext context = service.build(42L);

        assertThat(context.version()).isEqualTo("t1-vna");
        assertThat(context.visual().state()).isEqualTo("NOT_APPLICABLE");
        assertThat(context.visualEvidence()).isEmpty();
    }

    private MediaFile media(String sourceType) {
        MediaFile media = new MediaFile();
        media.setId(42L);
        media.setSourceType(sourceType);
        media.setTranscriptSource("ALIYUN_ASR");
        return media;
    }

    private TranscriptSegment segment() {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(10L);
        segment.setStartMs(0L);
        segment.setEndMs(1_000L);
        segment.setText("第一句");
        return segment;
    }

    private VideoKeyframe frame(Long id, String status, String text) {
        VideoKeyframe frame = new VideoKeyframe();
        frame.setId(id);
        frame.setTimestampMs(id * 1_000);
        frame.setObjectKey("evidence/42/" + id + ".jpg");
        frame.setOcrStatus(status);
        frame.setOcrConfidence(80.0);
        frame.setOcrText(text);
        return frame;
    }
}

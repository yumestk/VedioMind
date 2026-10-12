package com.example.server.service.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaVisualEvidence;
import com.example.server.entity.VideoKeyframe;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.service.TranscriptService;
import com.example.server.service.VisualEvidenceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VideoContextService {

    private final MediaFileMapper mediaFileMapper;
    private final TranscriptService transcriptService;
    private final MediaVisualEvidenceMapper visualEvidenceMapper;
    private final VideoKeyframeMapper keyframeMapper;

    public VideoContextService(
            MediaFileMapper mediaFileMapper,
            TranscriptService transcriptService,
            MediaVisualEvidenceMapper visualEvidenceMapper,
            VideoKeyframeMapper keyframeMapper
    ) {
        this.mediaFileMapper = mediaFileMapper;
        this.transcriptService = transcriptService;
        this.visualEvidenceMapper = visualEvidenceMapper;
        this.keyframeMapper = keyframeMapper;
    }

    public VideoContext build(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            throw new IllegalArgumentException("Media does not exist: " + mediaId);
        }

        List<VideoContext.TranscriptEvidence> transcripts = transcriptService.listByMediaId(mediaId)
                .stream()
                .map(segment -> new VideoContext.TranscriptEvidence(
                        segment.getId(), segment.getStartMs(), segment.getEndMs(), segment.getText()))
                .toList();
        int transcriptRevision = value(media.getTranscriptRevision());
        VideoContext.Modality transcript = new VideoContext.Modality(
                transcripts.isEmpty() ? "UNAVAILABLE" : "AVAILABLE",
                transcriptRevision,
                transcripts.size(),
                transcripts.size(),
                null
        );

        MediaVisualEvidence manifest = visualEvidenceMapper.selectById(mediaId);
        VideoContext.Modality visual = visualModality(media, manifest);
        List<VideoContext.VisualEvidence> frames = isComplete(manifest)
                ? keyframeMapper.selectList(
                                new LambdaQueryWrapper<VideoKeyframe>()
                                        .eq(VideoKeyframe::getMediaId, mediaId)
                                        .eq(VideoKeyframe::getProcessorVersion, manifest.getProcessorVersion())
                                        .orderByAsc(VideoKeyframe::getFrameIndex)
                        ).stream()
                        .map(frame -> new VideoContext.VisualEvidence(
                                frame.getId(),
                                frame.getTimestampMs(),
                                frame.getObjectKey(),
                                frame.getOcrStatus(),
                                frame.getOcrConfidence(),
                                "SUCCEEDED".equals(frame.getOcrStatus()) ? frame.getOcrText() : null
                        ))
                        .toList()
                : List.of();

        return new VideoContext(
                mediaId,
                version(transcriptRevision, media, manifest),
                media.getTranscriptSource(),
                transcript,
                visual,
                transcripts,
                frames
        );
    }

    private VideoContext.Modality visualModality(MediaFile media, MediaVisualEvidence evidence) {
        if ("EXTERNAL_URL".equals(media.getSourceType())) {
            return new VideoContext.Modality("NOT_APPLICABLE", 0, 0, 0, null);
        }
        if (evidence == null) {
            return new VideoContext.Modality("NOT_STARTED", 0, 0, 0, null);
        }
        if (isComplete(evidence)
                && !Integer.valueOf(VisualEvidenceService.PROCESSOR_VERSION)
                        .equals(evidence.getProcessorVersion())) {
            return new VideoContext.Modality(
                    "STALE", value(evidence.getRevision()), value(evidence.getFrameCount()), 0,
                    "Visual evidence must be regenerated"
            );
        }
        String state = switch (evidence.getStatus()) {
            case "READY" -> "AVAILABLE";
            case "PARTIAL" -> "PARTIAL";
            case "PROCESSING" -> "PROCESSING";
            case "FAILED" -> "FAILED";
            default -> "UNAVAILABLE";
        };
        return new VideoContext.Modality(
                state,
                value(evidence.getRevision()),
                value(evidence.getFrameCount()),
                value(evidence.getUsableOcrCount()),
                evidence.getErrorMessage()
        );
    }

    private boolean isComplete(MediaVisualEvidence evidence) {
        return evidence != null
                && ("READY".equals(evidence.getStatus()) || "PARTIAL".equals(evidence.getStatus()))
                && Integer.valueOf(VisualEvidenceService.PROCESSOR_VERSION)
                        .equals(evidence.getProcessorVersion());
    }

    private String version(int transcriptRevision, MediaFile media, MediaVisualEvidence evidence) {
        if ("EXTERNAL_URL".equals(media.getSourceType())) {
            return "t" + transcriptRevision + "-vna";
        }
        int visualRevision = evidence == null ? 0 : value(evidence.getRevision());
        int processorVersion = evidence == null
                ? VisualEvidenceService.PROCESSOR_VERSION
                : value(evidence.getProcessorVersion());
        return "t" + transcriptRevision + "-v" + visualRevision + "-p" + processorVersion;
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}

package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.MediaVisualEvidenceResponse;
import com.example.server.dto.VideoKeyframeResponse;
import com.example.server.entity.MediaVisualEvidence;
import com.example.server.entity.VideoKeyframe;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.service.ai.impl.FrameDifferenceHash;
import com.example.server.service.ai.impl.TesseractOcrService;
import com.example.server.service.ai.impl.VideoKeyframeExtractor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VisualEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(VisualEvidenceService.class);
    private static final String READY = "READY";
    private static final String OCR_SUCCEEDED = "SUCCEEDED";
    private static final String OCR_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    private static final String OCR_EMPTY = "EMPTY";
    private static final String OCR_FAILED = "FAILED";

    private final MediaVisualEvidenceMapper evidenceMapper;
    private final VideoKeyframeMapper keyframeMapper;
    private final VideoKeyframeExtractor keyframeExtractor;
    private final TesseractOcrService ocrService;
    private final ObjectStorageService objectStorageService;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            2,
            Thread.ofPlatform().name("visual-evidence-", 0).factory()
    );

    public VisualEvidenceService(
            MediaVisualEvidenceMapper evidenceMapper,
            VideoKeyframeMapper keyframeMapper,
            VideoKeyframeExtractor keyframeExtractor,
            TesseractOcrService ocrService,
            ObjectStorageService objectStorageService
    ) {
        this.evidenceMapper = evidenceMapper;
        this.keyframeMapper = keyframeMapper;
        this.keyframeExtractor = keyframeExtractor;
        this.ocrService = ocrService;
        this.objectStorageService = objectStorageService;
    }

    public CompletableFuture<Void> processAsync(Long mediaId, String videoUrl) {
        if (isReady(mediaId)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> process(mediaId, videoUrl), executor);
    }

    public MediaVisualEvidenceResponse get(Long mediaId) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        if (evidence == null) {
            return new MediaVisualEvidenceResponse(mediaId, "NOT_STARTED", 0, null, List.of());
        }

        List<VideoKeyframeResponse> frames = READY.equals(evidence.getStatus())
                ? listFrames(mediaId).stream()
                        .map(frame -> new VideoKeyframeResponse(
                                frame.getId(),
                                frame.getFrameIndex(),
                                frame.getTimestampMs(),
                                objectStorageService.createReadUrl(frame.getObjectKey()),
                                frame.getOcrStatus(),
                                frame.getOcrConfidence(),
                                frame.getOcrText()
                        ))
                        .toList()
                : List.of();
        return new MediaVisualEvidenceResponse(
                mediaId,
                evidence.getStatus(),
                evidence.getFrameCount(),
                evidence.getErrorMessage(),
                frames
        );
    }

    public void removeObjects(Long mediaId) {
        listFrames(mediaId).forEach(frame -> objectStorageService.remove(frame.getObjectKey()));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void process(Long mediaId, String videoUrl) {
        if (isReady(mediaId)) {
            return;
        }
        markProcessing(mediaId);
        VideoKeyframeExtractor.Extraction extraction = null;
        long startedAt = System.nanoTime();
        try {
            ocrService.ensureReady();
            extraction = keyframeExtractor.extract(videoUrl);
            List<VideoKeyframeExtractor.ExtractedFrame> retained = deduplicate(extraction.frames());
            if (retained.isEmpty()) {
                throw new IllegalStateException("No visual evidence could be extracted from the video");
            }

            Map<Long, VideoKeyframe> existingByTimestamp = new HashMap<>();
            for (VideoKeyframe frame : listFrames(mediaId)) {
                existingByTimestamp.put(frame.getTimestampMs(), frame);
            }

            Set<Long> selectedTimestamps = new HashSet<>();
            int ocrTextCount = 0;
            for (int index = 0; index < retained.size(); index++) {
                VideoKeyframeExtractor.ExtractedFrame extracted = retained.get(index);
                selectedTimestamps.add(extracted.timestampMs());
                VideoKeyframe existing = existingByTimestamp.get(extracted.timestampMs());
                if (isReusable(existing)) {
                    existing.setFrameIndex(index);
                    keyframeMapper.updateById(existing);
                    if (OCR_SUCCEEDED.equals(existing.getOcrStatus())) {
                        ocrTextCount++;
                    }
                    continue;
                }

                String ocrStatus;
                String ocrText = null;
                Double ocrConfidence = null;
                try {
                    TesseractOcrService.OcrResult result = ocrService.recognize(extracted.path());
                    ocrConfidence = result.confidence();
                    ocrText = result.text().isBlank() ? null : result.text();
                    ocrStatus = ocrText != null
                            ? OCR_SUCCEEDED
                            : result.detectedText() ? OCR_LOW_CONFIDENCE : OCR_EMPTY;
                    if (ocrText != null) {
                        ocrTextCount++;
                    }
                } catch (RuntimeException exception) {
                    ocrStatus = OCR_FAILED;
                    log.warn("OCR failed for media {} at {} ms: {}",
                            mediaId, extracted.timestampMs(), exception.getMessage());
                }

                String objectKey = objectKey(mediaId, extracted.timestampMs());
                objectStorageService.upload(extracted.path(), objectKey, "image/jpeg");
                saveFrame(existing, mediaId, index, extracted.timestampMs(), objectKey,
                        ocrStatus, ocrConfidence, ocrText);
            }

            for (VideoKeyframe stale : existingByTimestamp.values()) {
                if (!selectedTimestamps.contains(stale.getTimestampMs())) {
                    objectStorageService.remove(stale.getObjectKey());
                    keyframeMapper.deleteById(stale.getId());
                }
            }

            markReady(mediaId, retained.size());
            log.info("Visual evidence ready for media {}: {} candidates, {} retained, {} with OCR, {} ms",
                    mediaId,
                    extraction.frames().size(),
                    retained.size(),
                    ocrTextCount,
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException exception) {
            markFailed(mediaId, conciseMessage(exception));
            throw exception;
        } finally {
            if (extraction != null) {
                VideoKeyframeExtractor.deleteDirectory(extraction.directory());
            }
        }
    }

    private List<VideoKeyframeExtractor.ExtractedFrame> deduplicate(
            List<VideoKeyframeExtractor.ExtractedFrame> candidates
    ) {
        List<VideoKeyframeExtractor.ExtractedFrame> retained = new ArrayList<>();
        Long previousHash = null;
        for (VideoKeyframeExtractor.ExtractedFrame candidate : candidates) {
            long hash = FrameDifferenceHash.calculate(candidate.path());
            if (previousHash == null
                    || !FrameDifferenceHash.similar(previousHash, hash)) {
                retained.add(candidate);
                previousHash = hash;
            }
        }
        return retained;
    }

    private boolean isReusable(VideoKeyframe frame) {
        return frame != null
                && !OCR_FAILED.equals(frame.getOcrStatus())
                && frame.getOcrConfidence() != null
                && objectStorageService.objectSize(frame.getObjectKey()) != null;
    }

    private void saveFrame(
            VideoKeyframe frame,
            Long mediaId,
            int index,
            long timestampMs,
            String objectKey,
            String ocrStatus,
            Double ocrConfidence,
            String ocrText
    ) {
        VideoKeyframe target = frame == null ? new VideoKeyframe() : frame;
        target.setMediaId(mediaId);
        target.setFrameIndex(index);
        target.setTimestampMs(timestampMs);
        target.setObjectKey(objectKey);
        target.setOcrStatus(ocrStatus);
        target.setOcrConfidence(ocrConfidence);
        target.setOcrText(ocrText);
        if (target.getId() == null) {
            target.setCreatedAt(LocalDateTime.now());
            keyframeMapper.insert(target);
        } else {
            keyframeMapper.updateById(target);
        }
    }

    private List<VideoKeyframe> listFrames(Long mediaId) {
        return keyframeMapper.selectList(
                new LambdaQueryWrapper<VideoKeyframe>()
                        .eq(VideoKeyframe::getMediaId, mediaId)
                        .orderByAsc(VideoKeyframe::getFrameIndex)
        );
    }

    private boolean isReady(Long mediaId) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        return evidence != null && READY.equals(evidence.getStatus());
    }

    private void markProcessing(Long mediaId) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        LocalDateTime now = LocalDateTime.now();
        if (evidence == null) {
            evidence = new MediaVisualEvidence();
            evidence.setMediaId(mediaId);
            evidence.setStatus("PROCESSING");
            evidence.setFrameCount(0);
            evidence.setStartedAt(now);
            evidence.setUpdatedAt(now);
            evidenceMapper.insert(evidence);
            return;
        }
        evidence.setStatus("PROCESSING");
        evidence.setErrorMessage(null);
        evidence.setStartedAt(now);
        evidence.setFinishedAt(null);
        evidence.setUpdatedAt(now);
        evidenceMapper.updateById(evidence);
    }

    private void markReady(Long mediaId, int frameCount) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        LocalDateTime now = LocalDateTime.now();
        evidence.setStatus(READY);
        evidence.setFrameCount(frameCount);
        evidence.setErrorMessage(null);
        evidence.setFinishedAt(now);
        evidence.setUpdatedAt(now);
        evidenceMapper.updateById(evidence);
    }

    private void markFailed(Long mediaId, String errorMessage) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        if (evidence == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        evidence.setStatus("FAILED");
        evidence.setErrorMessage(errorMessage);
        evidence.setFinishedAt(now);
        evidence.setUpdatedAt(now);
        evidenceMapper.updateById(evidence);
    }

    private String objectKey(Long mediaId, long timestampMs) {
        return "evidence/" + mediaId + "/frames/" + timestampMs + ".jpg";
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}

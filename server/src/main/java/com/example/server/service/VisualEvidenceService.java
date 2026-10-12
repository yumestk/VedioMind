package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.entity.MediaVisualEvidence;
import com.example.server.entity.VideoKeyframe;
import com.example.server.mapper.MediaVisualEvidenceMapper;
import com.example.server.mapper.VideoKeyframeMapper;
import com.example.server.service.ai.impl.FrameDifferenceHash;
import com.example.server.service.ai.impl.TesseractOcrService;
import com.example.server.service.ai.impl.VideoKeyframeExtractor;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class VisualEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(VisualEvidenceService.class);
    private static final String READY = "READY";
    private static final String PARTIAL = "PARTIAL";
    public static final int PROCESSOR_VERSION = 2;
    private static final String OCR_SUCCEEDED = "SUCCEEDED";
    private static final String OCR_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    private static final String OCR_EMPTY = "EMPTY";
    private static final String OCR_FAILED = "FAILED";

    private final MediaVisualEvidenceMapper evidenceMapper;
    private final VideoKeyframeMapper keyframeMapper;
    private final VideoKeyframeExtractor keyframeExtractor;
    private final TesseractOcrService ocrService;
    private final ObjectStorageService objectStorageService;
    private final RedissonClient redissonClient;
    private final ExecutorService executor = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),
            Thread.ofPlatform().name("visual-evidence-", 0).factory()
    );

    public VisualEvidenceService(
            MediaVisualEvidenceMapper evidenceMapper,
            VideoKeyframeMapper keyframeMapper,
            VideoKeyframeExtractor keyframeExtractor,
            TesseractOcrService ocrService,
            ObjectStorageService objectStorageService,
            RedissonClient redissonClient
    ) {
        this.evidenceMapper = evidenceMapper;
        this.keyframeMapper = keyframeMapper;
        this.keyframeExtractor = keyframeExtractor;
        this.ocrService = ocrService;
        this.objectStorageService = objectStorageService;
        this.redissonClient = redissonClient;
    }

    public Future<Void> processAsync(Long mediaId, String videoUrl) {
        if (isReady(mediaId)) {
            return CompletableFuture.completedFuture(null);
        }
        return executor.submit(() -> {
            processWithLock(mediaId, videoUrl);
            return null;
        });
    }

    public void removeObjects(Long mediaId) {
        RLock lock = redissonClient.getLock("lock:visual-evidence:" + mediaId);
        lock.lock();
        try {
            listFrames(mediaId).forEach(frame -> objectStorageService.remove(frame.getObjectKey()));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void processWithLock(Long mediaId, String videoUrl) {
        RLock lock = redissonClient.getLock("lock:visual-evidence:" + mediaId);
        boolean locked = false;
        try {
            lock.lockInterruptibly();
            locked = true;
            process(mediaId, videoUrl);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Visual evidence processing was interrupted", exception);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void process(Long mediaId, String videoUrl) {
        if (isReady(mediaId)) {
            return;
        }
        markProcessing(mediaId);
        VideoKeyframeExtractor.Extraction extraction = null;
        long startedAt = System.nanoTime();
        QualityCounts counts = new QualityCounts();
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
            for (int index = 0; index < retained.size(); index++) {
                VideoKeyframeExtractor.ExtractedFrame extracted = retained.get(index);
                selectedTimestamps.add(extracted.timestampMs());
                VideoKeyframe existing = existingByTimestamp.get(extracted.timestampMs());
                if (isReusable(existing)) {
                    existing.setFrameIndex(index);
                    keyframeMapper.updateById(existing);
                    counts.add(existing.getOcrStatus());
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
                } catch (RuntimeException exception) {
                    ocrStatus = OCR_FAILED;
                    log.warn("OCR failed for media {} at {} ms: {}",
                            mediaId, extracted.timestampMs(), exception.getMessage());
                }

                String objectKey = objectKey(mediaId, extracted.timestampMs());
                objectStorageService.upload(extracted.path(), objectKey, "image/jpeg");
                saveFrame(existing, mediaId, index, extracted.timestampMs(), objectKey,
                        ocrStatus, ocrConfidence, ocrText);
                counts.add(ocrStatus);
            }

            for (VideoKeyframe stale : existingByTimestamp.values()) {
                if (!selectedTimestamps.contains(stale.getTimestampMs())) {
                    objectStorageService.remove(stale.getObjectKey());
                    keyframeMapper.deleteById(stale.getId());
                }
            }

            markComplete(mediaId, retained.size(), counts);
            log.info("Visual evidence ready for media {}: {} candidates, {} retained, {} with OCR, {} ms",
                    mediaId,
                    extraction.frames().size(),
                    retained.size(),
                    counts.usable,
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
                && Integer.valueOf(PROCESSOR_VERSION).equals(frame.getProcessorVersion())
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
        target.setProcessorVersion(PROCESSOR_VERSION);
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
        return evidence != null
                && READY.equals(evidence.getStatus())
                && Integer.valueOf(PROCESSOR_VERSION).equals(evidence.getProcessorVersion());
    }

    private void markProcessing(Long mediaId) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        LocalDateTime now = LocalDateTime.now();
        if (evidence == null) {
            evidence = new MediaVisualEvidence();
            evidence.setMediaId(mediaId);
            evidence.setStatus("PROCESSING");
            evidence.setProcessorVersion(PROCESSOR_VERSION);
            evidence.setRevision(0);
            evidence.setFrameCount(0);
            evidence.setUsableOcrCount(0);
            evidence.setLowConfidenceCount(0);
            evidence.setEmptyOcrCount(0);
            evidence.setFailedOcrCount(0);
            evidence.setStartedAt(now);
            evidence.setUpdatedAt(now);
            evidenceMapper.insert(evidence);
            return;
        }
        evidence.setStatus("PROCESSING");
        evidence.setProcessorVersion(PROCESSOR_VERSION);
        evidence.setFrameCount(0);
        evidence.setUsableOcrCount(0);
        evidence.setLowConfidenceCount(0);
        evidence.setEmptyOcrCount(0);
        evidence.setFailedOcrCount(0);
        evidence.setErrorMessage(null);
        evidence.setStartedAt(now);
        evidence.setFinishedAt(null);
        evidence.setUpdatedAt(now);
        evidenceMapper.updateById(evidence);
    }

    private void markComplete(Long mediaId, int frameCount, QualityCounts counts) {
        MediaVisualEvidence evidence = evidenceMapper.selectById(mediaId);
        LocalDateTime now = LocalDateTime.now();
        evidence.setStatus(counts.failed > 0 ? PARTIAL : READY);
        evidence.setProcessorVersion(PROCESSOR_VERSION);
        evidence.setRevision(evidence.getRevision() == null ? 1 : evidence.getRevision() + 1);
        evidence.setFrameCount(frameCount);
        evidence.setUsableOcrCount(counts.usable);
        evidence.setLowConfidenceCount(counts.lowConfidence);
        evidence.setEmptyOcrCount(counts.empty);
        evidence.setFailedOcrCount(counts.failed);
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

    private static final class QualityCounts {
        private int usable;
        private int lowConfidence;
        private int empty;
        private int failed;

        private void add(String status) {
            switch (status) {
                case OCR_SUCCEEDED -> usable++;
                case OCR_LOW_CONFIDENCE -> lowConfidence++;
                case OCR_EMPTY -> empty++;
                case OCR_FAILED -> failed++;
                default -> throw new IllegalArgumentException("Unknown OCR status: " + status);
            }
        }
    }
}

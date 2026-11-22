package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.service.ai.impl.AliyunSpeechTranscriber;
import com.example.server.service.ai.impl.AsrSlices;
import com.example.server.service.ai.impl.FfmpegAudioExtractor;
import com.example.server.service.ai.impl.TesseractOcrService;
import com.example.server.service.ai.impl.VideoKeyframeExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import static org.junit.jupiter.api.Assertions.*;

@Tag("eval")
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class MultimodalV2EvalTest {
    @Test
    void localOcrMatrix() throws Exception {
        var settings = new EvalSettings();
        var video = Path.of(EvalSettings.requiredProperty("eval.videoPath")).toRealPath();
        assertEquals(43, settings.mediaId);
        settings.verifyVideo(video);
        String ffmpeg = settings.get("tool.ffmpeg.dir", "FFMPEG_DIR", "/opt/homebrew/bin");
        var extractor = new VideoKeyframeExtractor(ffmpeg);
        var ocr = new TesseractOcrService(settings.get("tool.tesseract.path", "TESSERACT_PATH", "tesseract"));
        ocr.ensureReady();
        long begin = System.nanoTime();
        var extraction = extractor.extract(video.toString());
        double scene = seconds(begin);
        var visual = new VisualEvidenceService(null, null, extractor, ocr, null, null);
        try {
            begin = System.nanoTime();
            var kept = visual.deduplicate(extraction.frames());
            double hash = seconds(begin);
            assertEquals(198, extraction.frames().size());
            assertEquals(188, kept.size());
            var paths = kept.stream().map(VideoKeyframeExtractor.ExtractedFrame::path).toList();
            List<TesseractOcrService.FrameOcr> baseline = List.of();
            var rows = new ArrayList<String>();
            for (int workers : List.of(1, 2, 4, 6)) {
                begin = System.nanoTime();
                var result = TesseractOcrService.recognizeOrdered(ocr, paths, workers, new Semaphore(workers));
                double elapsed = seconds(begin);
                assertEquals(0, result.stream().filter(r -> r.error() != null).count());
                assertEquals(183, result.stream().filter(r -> r.result().detectedText()).count());
                assertEquals(161, result.stream().filter(r -> "SUCCEEDED".equals(r.status())).count());
                if (workers == 1) baseline = result;
                else assertEquals(baseline, result, "Text, confidence and status must match at every timestamp");
                rows.add(String.format(Locale.ROOT, "%d\t%.6f", workers, elapsed));
                System.out.println("OCR matrix " + rows.getLast());
            }
            EvalSettings.write("multimodal-v2-ocr.tsv", String.format(Locale.ROOT, "scene\t%.6f%nhash\t%.6f%n", scene, hash)
                    + String.join("\n", rows) + "\n");
        } finally {
            visual.shutdown();
            VideoKeyframeExtractor.deleteDirectory(extraction.directory());
        }
    }

    @Test
    void localCutPlan() throws Exception {
        var settings = new EvalSettings();
        var video = Path.of(EvalSettings.requiredProperty("eval.videoPath")).toRealPath();
        assertEquals(43, settings.mediaId);
        settings.verifyVideo(video);
        String ffmpeg = settings.get("tool.ffmpeg.dir", "FFMPEG_DIR", "/opt/homebrew/bin");
        var audio = new FfmpegAudioExtractor(ffmpeg).extract(video.toString());
        try {
            var plan = AsrSlices.plan(audio.toPath(), Path.of(ffmpeg, "ffmpeg").toString());
            assertEquals(10, plan.slices().size());
            assertEquals(9, plan.cuts().size());
            assertTrue(plan.cuts().stream().allMatch(AsrSlices.Cut::silent), "Expected nine quiet cuts in this file");
            long next = 0;
            for (var slice : plan.slices()) {
                assertEquals(next, slice.startSample());
                assertTrue(slice.endSample() > next);
                assertTrue(slice.endSample() - next < 600L * 16000);
                next = slice.endSample();
            }
            assertEquals(plan.samples(), next);
            var baseline = subtitleMetrics(baseline(settings), plan.samples());
            assertEquals(new SubtitleMetrics(524, 26097, 5405620, 0, 0, 0), baseline);
            EvalSettings.write("multimodal-v2-plan.json", JSON.toJSONString(plan));
        } finally {
            Files.deleteIfExists(audio.toPath());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "eval.paidAsr", matches = "true")
    void fullBenchmark() throws Exception {
        var settings = new EvalSettings();
        var video = Path.of(EvalSettings.requiredProperty("eval.videoPath")).toRealPath();
        assertEquals(43, settings.mediaId);
        settings.verifyVideo(video);
        var preflight = Files.readString(Path.of("target", "eval", "multimodal-v2-plan.json"));
        var matrix = Files.readAllLines(Path.of("target", "eval", "multimodal-v2-ocr.tsv"));
        int ocrWorkers = matrix.subList(2, matrix.size()).stream().map(row -> row.split("\t"))
                .min(java.util.Comparator.comparingDouble(v -> Double.parseDouble(v[1])))
                .map(v -> Integer.parseInt(v[0])).orElseThrow();
        var dir = Files.createDirectories(Path.of("target", "eval", "multimodal-v2-paid"));
        String ffmpeg = settings.get("tool.ffmpeg.dir", "FFMPEG_DIR", "/opt/homebrew/bin");
        var extractor = new VideoKeyframeExtractor(ffmpeg);
        var ocr = new TesseractOcrService(settings.get("tool.tesseract.path", "TESSERACT_PATH", "tesseract"));
        ocr.ensureReady();
        var redis = settings.redis();
        try {
            var speech = new AliyunSpeechTranscriber(settings.aliyunKey(),
                    new AiRetryExecutor(new AiRateLimitService(redis, 5, 30, 30, 6, 60)), 5, ffmpeg,
                    settings.get("ai.asr.websocket-url", "AI_ASR_WEBSOCKET_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference"));
            try (var executor = Executors.newFixedThreadPool(2)) {
                long fullStart = System.nanoTime();
                var audio = new FfmpegAudioExtractor(ffmpeg).extract(video.toString());
                double audioSeconds = seconds(fullStart);
                try {
                    // A single authorized paid run. Slice artifacts stay local for diagnosis.
                    Files.createFile(dir.resolve("started"));
                    long parallelStart = System.nanoTime();
                    var asr = executor.submit(() -> {
                        long start = System.nanoTime();
                        var plan = speech.plan(audio);
                        assertEquals(preflight, JSON.toJSONString(plan));
                        double planning = seconds(start);
                        var transcript = speech.transcribePlanned(audio, plan, (slice, lines) -> {
                            try {
                                Files.writeString(dir.resolve("slice-" + slice.index() + ".json"), JSON.toJSONString(lines));
                            } catch (java.io.IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                        Files.writeString(dir.resolve("merged.json"), JSON.toJSONString(transcript.segments()));
                        return new double[]{seconds(start), planning};
                    });
                    var frames = executor.submit(() -> visualRun(video, extractor, ocr, ocrWorkers));
                    try {
                        var visual = frames.get();
                        var speechPerf = asr.get();
                        double parallel = seconds(parallelStart);
                        EvalSettings.write("multimodal-v2-timing.json", JSON.toJSONString(java.util.Map.of(
                                "audio", audioSeconds, "asr", speechPerf[0], "planning", speechPerf[1],
                                "visual", visual.getDoubleValue("total"), "parallel", parallel, "full", seconds(fullStart),
                                "ocrWorkers", ocrWorkers, "visualBreakdown", visual)));
                        var plan = JSON.parseObject(preflight, AsrSlices.Plan.class);
                        var merged = JSON.parseArray(Files.readString(dir.resolve("merged.json")), TranscriptSegmentDraft.class);
                        verifySubtitles(settings, plan, merged);
                    } catch (Exception error) {
                        asr.cancel(true);
                        frames.cancel(true);
                        throw error;
                    }
                } finally {
                    executor.shutdownNow();
                    boolean interrupted = Thread.interrupted();
                    try {
                        while (!executor.isTerminated()) {
                            try {
                                executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException error) {
                                interrupted = true;
                            }
                        }
                        Files.deleteIfExists(audio.toPath());
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            redis.shutdown();
        }
    }

    private static List<TranscriptSegmentDraft> baseline(EvalSettings settings) {
        return new org.springframework.jdbc.core.JdbcTemplate(settings.dataSource()).query("""
                SELECT start_ms,end_ms,text FROM transcript_segments WHERE media_id=? ORDER BY segment_index
                """, (row, n) -> new TranscriptSegmentDraft(row.getLong(1), row.getLong(2), row.getString(3)), settings.mediaId);
    }

    private static void verifySubtitles(EvalSettings settings, AsrSlices.Plan plan,
                                        List<TranscriptSegmentDraft> merged) throws Exception {
        var before = baseline(settings);
        var old = subtitleMetrics(before, plan.samples());
        var current = subtitleMetrics(merged, plan.samples());
        var cuts = plan.cuts().stream().map(c -> {
            long cutMs = AsrSlices.millis(c.sample());
            return JSONObject.of("cutMs", cutMs, "baseline", nearCut(before, cutMs), "merged", nearCut(merged, cutMs));
        }).toList();
        double drift = Math.abs(current.normalizedChars() - old.normalizedChars()) / (double) old.normalizedChars();
        long tailDrift = Math.abs(current.lastEndMs() - old.lastEndMs());
        EvalSettings.write("multimodal-v2-quality.json", JSON.toJSONString(JSONObject.of("baseline", old,
                "merged", current, "cuts", cuts, "charDrift", drift, "lastEndDriftMs", tailDrift)));
        assertAll(() -> assertTrue(drift <= 0.03, "Normalized character drift"),
                () -> assertTrue(tailDrift <= 2000, "Last ASR timestamp drift"),
                () -> assertEquals(0, current.invalid() + current.overlaps() + current.nonIncreasing()));
    }

    private static List<String> nearCut(List<TranscriptSegmentDraft> lines, long cutMs) {
        return lines.stream().filter(l -> l.endMs() > cutMs - 15_000 && l.startMs() < cutMs + 15_000)
                .map(TranscriptSegmentDraft::text).toList();
    }

    private static SubtitleMetrics subtitleMetrics(List<TranscriptSegmentDraft> lines, long samples) {
        int glyphs = 0;
        int invalid = 0;
        int overlaps = 0;
        int nonIncreasing = 0;
        TranscriptSegmentDraft previous = null;
        long last = 0;
        for (var line : lines) {
            var normalized = AsrSlices.normalize(line.text());
            glyphs += normalized.codePointCount(0, normalized.length());
            if (line.startMs() < 0 || line.startMs() >= line.endMs() || line.endMs() > AsrSlices.millis(samples)) invalid++;
            if (previous != null && line.startMs() < previous.endMs()) overlaps++;
            if (previous != null && line.startMs() <= previous.startMs()) nonIncreasing++;
            last = Math.max(last, line.endMs());
            previous = line;
        }
        return new SubtitleMetrics(lines.size(), glyphs, last, invalid, overlaps, nonIncreasing);
    }

    public record SubtitleMetrics(int count, int normalizedChars, long lastEndMs, int invalid, int overlaps, int nonIncreasing) {}

    private static JSONObject visualRun(Path video, VideoKeyframeExtractor extractor, TesseractOcrService ocr, int workers) {
        long start = System.nanoTime();
        var extraction = extractor.extract(video.toString());
        double scene = seconds(start);
        var visual = new VisualEvidenceService(null, null, extractor, ocr, null, null);
        try {
            long hashStart = System.nanoTime();
            var kept = visual.deduplicate(extraction.frames());
            double hash = seconds(hashStart);
            long ocrStart = System.nanoTime();
            var result = TesseractOcrService.recognizeOrdered(ocr, kept.stream().map(VideoKeyframeExtractor.ExtractedFrame::path).toList(),
                    workers, new Semaphore(workers));
            double ocrSeconds = seconds(ocrStart);
            assertEquals(198, extraction.frames().size());
            assertEquals(188, kept.size());
            assertEquals(0, result.stream().filter(r -> r.error() != null).count());
            assertEquals(183, result.stream().filter(r -> r.result().detectedText()).count());
            assertEquals(161, result.stream().filter(r -> "SUCCEEDED".equals(r.status())).count());
            return JSONObject.of("scene", scene, "dhash", hash, "ocr", ocrSeconds, "total", seconds(start));
        } finally {
            visual.shutdown();
            VideoKeyframeExtractor.deleteDirectory(extraction.directory());
        }
    }

    static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }
}

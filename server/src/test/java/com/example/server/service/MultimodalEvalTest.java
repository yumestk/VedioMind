package com.example.server.service;

import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.impl.AliyunSpeechTranscriber;
import com.example.server.service.ai.impl.FfmpegAudioExtractor;
import com.example.server.service.ai.impl.TesseractOcrService;
import com.example.server.service.ai.impl.VideoKeyframeExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("eval")
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class MultimodalEvalTest {
    @Test
    void benchmark() throws Exception {
        var settings = new EvalSettings();
        var video = Path.of(EvalSettings.requiredProperty("eval.videoPath")).toRealPath();
        settings.verifyVideo(video);
        var ffmpeg = settings.get("tool.ffmpeg.dir", "FFMPEG_DIR", "/opt/homebrew/bin");
        var extractor = new VideoKeyframeExtractor(ffmpeg);
        var ocr = new TesseractOcrService(settings.get("tool.tesseract.path", "TESSERACT_PATH", "tesseract"));
        ocr.ensureReady();
        var extractionStart = System.nanoTime();
        var audio = new FfmpegAudioExtractor(ffmpeg).extract(video.toString());
        double audioSeconds = secondsSince(extractionStart);
        var gate = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        var redis = settings.redis();
        try {
            var speech = new AliyunSpeechTranscriber(settings.aliyunKey(), new AiRetryExecutor(
                    new AiRateLimitService(redis, 5, 30, 30, 6, 60)));
            var asr = workers.submit(() -> {
                gate.await();
                long start = System.nanoTime();
                var result = speech.transcribe(audio);
                var measured = new Asr(secondsSince(start), result.segments().size(),
                        result.segments().stream().mapToLong(segment -> segment.endMs()).max().orElse(0));
                System.out.println("Eval ASR finished: " + measured);
                return measured;
            });
            var visual = workers.submit(() -> {
                gate.await();
                var measured = measureVisual(video, extractor, ocr);
                System.out.println("Eval visual finished: " + measured);
                return measured;
            });
            long start = System.nanoTime();
            gate.countDown();
            var picture = visual.get(60, TimeUnit.MINUTES);
            EvalSettings.write("eval-multimodal.md", visualReport(picture));
            Asr audioResult = asr.get(60, TimeUnit.MINUTES);
            double parallel = Math.max(audioResult.finishedAt, picture.finishedAt) - start;
            parallel /= 1_000_000_000d;
            assertFalse(audioResult.segments == 0, "Empty ASR result");
            double serial = audioResult.seconds + picture.seconds;
            EvalSettings.write("eval-multimodal.md", visualReport(picture) + String.format(Locale.ROOT, """

                    | 计时项 | 单次实测 |
                    |---|---:|
                    | FFmpeg 音频提取（计时前单独执行） | %.3f s |
                    | ASR 转写 `t_asr` | %.3f s |
                    | 抽帧、dHash、OCR `t_visual` | %.3f s |
                    | 并行墙钟 `t_parallel` | %.3f s |
                    | 串行参照 `t_asr + t_visual` | %.3f s |
                    | 相对串行参照的耗时降幅 | %.2f%% |
                    | ASR 实际返回字幕段数 | %d |
                    | ASR 最后一段结束位置 | %.3f s |

                    两分支由同一闩锁启动。串行值由本轮两分支的实测耗时相加，统计范围为音频就绪后的解析阶段。
                    """, audioSeconds, audioResult.seconds, picture.seconds, parallel, serial,
                    100 * (serial - parallel) / serial, audioResult.segments, audioResult.lastEndMs / 1000d));
        } finally {
            workers.shutdownNow();
            try {
                workers.awaitTermination(10, TimeUnit.SECONDS);
            } finally {
                redis.shutdown();
                Files.deleteIfExists(audio.toPath());
            }
        }
    }

    private static Visual measureVisual(Path video, VideoKeyframeExtractor extractor, TesseractOcrService ocr) {
        var service = new VisualEvidenceService(null, null, extractor, ocr, null, null);
        long start = System.nanoTime();
        var extraction = extractor.extract(video.toString());
        try {
            // The production deduplication step is private and independent of persistence.
            List<VideoKeyframeExtractor.ExtractedFrame> retained =
                    ReflectionTestUtils.invokeMethod(service, "deduplicate", extraction.frames());
            int detected = 0;
            int usable = 0;
            int failed = 0;
            for (var frame : retained) {
                try {
                    var result = ocr.recognize(frame.path());
                    if (result.detectedText()) detected++;
                    if (!result.text().isBlank()) usable++;
                } catch (RuntimeException error) {
                    System.err.println("Eval OCR failed at " + frame.timestampMs() + " ms: " + error.getMessage());
                    failed++;
                }
            }
            return new Visual(secondsSince(start), extraction.frames().size(), retained.size(),
                    detected, usable, failed);
        } finally {
            VideoKeyframeExtractor.deleteDirectory(extraction.directory());
            service.shutdown();
        }
    }

    private static String visualReport(Visual result) {
        return String.format(Locale.ROOT, """
                ## 多模态解析

                | 指标 | 单次实测 |
                |---|---:|
                | FFmpeg 场景抽帧候选 | %d |
                | dHash 后保留 | %d |
                | 关键帧去重率 | %s |
                | OCR 检测到文字 | %d |
                | OCR 通过质量门槛 | %d |
                | OCR 执行失败 | %d |
                | OCR 无文字 | %d |
                | OCR 噪声过滤率 | %s |

                去重率以 FFmpeg 输出的场景候选为分母。OCR 过滤率为（检测到文字帧 − 通过质量门槛帧）÷ 检测到文字帧。
                """, result.candidates, result.retained, EvalSettings.percent(result.candidates - result.retained, result.candidates),
                result.detected, result.usable, result.failed, result.retained - result.detected - result.failed,
                EvalSettings.percent(result.detected - result.usable, result.detected));
    }

    private static double secondsSince(long start) {
        return (System.nanoTime() - start) / 1_000_000_000d;
    }

    private record Asr(double seconds, int segments, long lastEndMs, long finishedAt) {
        Asr(double seconds, int segments, long lastEndMs) {
            this(seconds, segments, lastEndMs, System.nanoTime());
        }
    }

    private record Visual(double seconds, int candidates, int retained, int detected, int usable, int failed, long finishedAt) {
        Visual(double seconds, int candidates, int retained, int detected, int usable, int failed) {
            this(seconds, candidates, retained, detected, usable, failed, System.nanoTime());
        }
    }
}

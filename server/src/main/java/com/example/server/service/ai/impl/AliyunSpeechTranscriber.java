package com.example.server.service.ai.impl;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.SpeechTranscriber;
import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.service.ai.TranscriptionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class AliyunSpeechTranscriber implements SpeechTranscriber {

    static final String MODEL = "paraformer-realtime-v2";
    static final int SAMPLE_RATE = 16_000;

    private final String apiKey;
    private final AiRetryExecutor retryExecutor;
    private final int workers;
    private final java.util.concurrent.Semaphore active;
    private final String ffmpeg;
    private final String streamUrl;

    public AliyunSpeechTranscriber(
            @Value("${ai.aliyun.api-key}") String apiKey,
            AiRetryExecutor retryExecutor
    ) {
        this(apiKey, retryExecutor, 1, "/opt/homebrew/bin", "wss://dashscope.aliyuncs.com/api-ws/v1/inference");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AliyunSpeechTranscriber(@Value("${ai.aliyun.api-key}") String apiKey, AiRetryExecutor retryExecutor,
            @Value("${ai.asr.workers:5}") int workers,
            @Value("${tool.ffmpeg.dir}") String ffmpegDirectory,
            @Value("${ai.asr.websocket-url:wss://dashscope.aliyuncs.com/api-ws/v1/inference}") String streamUrl) {
        if (workers < 1) throw new IllegalArgumentException("ASR workers must be positive");
        this.workers = workers;
        this.active = new java.util.concurrent.Semaphore(workers, true);
        this.ffmpeg = ffmpegDirectory == null || ffmpegDirectory.isBlank() ? "ffmpeg"
                : java.nio.file.Path.of(ffmpegDirectory, "ffmpeg").toString();
        this.streamUrl = streamUrl;
        this.apiKey = apiKey;
        this.retryExecutor = retryExecutor;
    }

    @Override
    public TranscriptionResult transcribe(File audioFile) {
        if (audioFile == null || !audioFile.isFile() || audioFile.length() == 0) {
            throw new IllegalArgumentException("PCM audio file does not exist or is empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ALIYUN_API_KEY is not configured");
        }

        if (workers == 1 || audioFile.length() <= 600L * SAMPLE_RATE * 2) {
            return withActive(() -> retryExecutor.execute(AiRateLimitService.ALIYUN_ASR, () -> execute(audioFile)));
        }
        try {
            return transcribePlanned(audioFile, plan(audioFile));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Could not prepare ASR slices", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR interrupted", error);
        }
    }

    public AsrSlices.Plan plan(File audioFile) throws java.io.IOException, InterruptedException {
        return AsrSlices.plan(audioFile.toPath(), ffmpeg);
    }

    public TranscriptionResult transcribePlanned(File audioFile, AsrSlices.Plan plan)
            throws java.io.IOException, InterruptedException {
        return transcribePlanned(audioFile, plan, (slice, sentences) -> {});
    }

    public TranscriptionResult transcribePlanned(File audioFile, AsrSlices.Plan plan,
            java.util.function.BiConsumer<AsrSlices.Slice, List<TranscriptSegmentDraft>> completed)
            throws java.io.IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ALIYUN_API_KEY is not configured");
        if (plan.slices().size() == 1) return transcribe(audioFile);
        if (audioFile.length() != plan.samples() * 2) throw new IllegalArgumentException("PCM does not match ASR plan");
        var directory = java.nio.file.Files.createTempDirectory("asr-slices-");
        try {
            return AsrSlices.merge(plan, runSlices(plan, slice -> {
                var path = directory.resolve(slice.index() + ".pcm");
                AsrSlices.copy(audioFile.toPath(), path, slice);
                var sentences = transcribeSlice(path, slice.index());
                completed.accept(slice, sentences);
                return sentences;
            }));
        } finally {
            try (var paths = java.nio.file.Files.list(directory)) {
                for (var path : paths.toList()) java.nio.file.Files.delete(path);
            }
            java.nio.file.Files.delete(directory);
        }
    }

    @FunctionalInterface
    interface SliceWork {
        List<TranscriptSegmentDraft> run(AsrSlices.Slice slice) throws Exception;
    }

    List<AsrSlices.Piece> runSlices(AsrSlices.Plan plan, SliceWork work) throws InterruptedException {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(workers, plan.slices().size()));
        try (executor) {
            var completed = new java.util.concurrent.ExecutorCompletionService<AsrSlices.Piece>(executor);
            for (var slice : plan.slices()) {
                completed.submit(() -> {
                    try {
                        return new AsrSlices.Piece(slice, work.run(slice));
                    } catch (Exception error) {
                        throw new IllegalStateException("ASR slice " + (slice.index() + 1) + " failed", error);
                    }
                });
            }
            var results = new ArrayList<AsrSlices.Piece>();
            try {
                for (int i = 0; i < plan.slices().size(); i++) results.add(completed.take().get());
                return List.copyOf(results);
            } catch (java.util.concurrent.ExecutionException error) {
                throw new IllegalStateException(error.getCause().getMessage(), error.getCause());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private List<TranscriptSegmentDraft> transcribeSlice(java.nio.file.Path path, int index) {
        return retrySlice(() -> recognizeSlice(path), () -> {
            log.info("Starting Paraformer slice {} ({} PCM bytes)", index + 1, path.toFile().length());
        });
    }

    private List<TranscriptSegmentDraft> recognizeSlice(java.nio.file.Path path) {
        try {
            return ParaformerStream.recognize(apiKey, streamUrl, path);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR slice interrupted", error);
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AliyunSpeechTranscriber.class);

    <T> T retrySlice(java.util.function.Supplier<T> send, Runnable beforeSend) {
        var deadline = java.time.Instant.now().plusSeconds(900);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        while (true) {
            try {
                return withActive(() -> retryExecutor.execute(AiRateLimitService.ALIYUN_ASR, () -> {
                    if (attempts.getAndIncrement() >= 3) throw new IllegalStateException("ASR slice retry limit");
                    beforeSend.run();
                }, send, deadline));
            } catch (AiRateLimitService.AiRateLimitExceededException wait) {
                AiRetryExecutor.remainingMillis(deadline);
                try {
                    Thread.sleep(Math.min(5, wait.retryAfterSeconds()) * 1000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ASR quota wait interrupted", error);
                }
            }
        }
    }

    private <T> T withActive(java.util.function.Supplier<T> work) {
        try {
            active.acquire();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR interrupted while waiting for a worker", error);
        }
        try {
            return work.get();
        } finally {
            active.release();
        }
    }

    private TranscriptionResult execute(File audioFile) {
        try {
            RecognitionParam parameter = RecognitionParam.builder()
                    .apiKey(apiKey)
                    .model(MODEL)
                    .format("pcm")
                    .sampleRate(SAMPLE_RATE)
                    .build();
            String responseJson = new Recognition().call(parameter, audioFile);
            return parseResponse(responseJson);
        } catch (ApiException exception) {
            int statusCode = exception.getStatus() == null ? -1 : exception.getStatus().getStatusCode();
            throw AiProviderException.forStatus(
                    "Aliyun speech recognition failed: " + conciseMessage(exception),
                    exception,
                    statusCode,
                    0
            );
        } catch (RuntimeException exception) {
            throw new AiProviderException(
                    "Aliyun speech recognition failed: " + conciseMessage(exception),
                    exception,
                    false,
                    0
            );
        }
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    TranscriptionResult parseResponse(String responseJson) {
        JSONObject response = JSON.parseObject(responseJson);
        JSONArray sentences = response == null ? null : response.getJSONArray("sentences");
        if (sentences == null || sentences.isEmpty()) {
            throw new IllegalStateException("Aliyun speech recognition returned no sentences");
        }

        List<TranscriptSegmentDraft> segments = new ArrayList<>();
        for (int index = 0; index < sentences.size(); index++) {
            JSONObject sentence = sentences.getJSONObject(index);
            Long startMs = sentence.getLong("begin_time");
            Long endMs = sentence.getLong("end_time");
            String text = sentence.getString("text");
            if (startMs == null || endMs == null || text == null || text.isBlank()) {
                continue;
            }
            segments.add(new TranscriptSegmentDraft(startMs, endMs, text));
        }

        segments.sort(Comparator.comparingLong(TranscriptSegmentDraft::startMs));
        if (segments.isEmpty()) {
            throw new IllegalStateException("Aliyun speech recognition returned no complete sentences");
        }
        return new TranscriptionResult(segments);
    }
}

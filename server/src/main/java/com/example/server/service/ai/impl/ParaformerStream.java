package com.example.server.service.ai.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.TranscriptSegmentDraft;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ParaformerStream {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
    private static final int PACKET_BYTES = 16_384;
    private static final int PACKET_INTERVAL_MS = 100;

    private ParaformerStream() {}

    static List<TranscriptSegmentDraft> recognize(String key, String url, Path path) throws InterruptedException {
        String id = UUID.randomUUID().toString();
        var started = new CompletableFuture<Void>();
        var finished = new CompletableFuture<List<TranscriptSegmentDraft>>();
        var sentences = new ArrayList<TranscriptSegmentDraft>();
        var request = new Request.Builder().url(url).header("Authorization", "Bearer " + key).build();
        var socket = HTTP.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket ws, String message) {
                try {
                    var frame = JSON.parseObject(message);
                    var header = frame.getJSONObject("header");
                    if (!id.equals(header.getString("task_id"))) throw new IllegalStateException("Wrong ASR task id");
                    switch (header.getString("event")) {
                        case "task-started" -> started.complete(null);
                        case "result-generated" -> {
                            var sentence = frame.getJSONObject("payload").getJSONObject("output").getJSONObject("sentence");
                            appendSentence(sentences, sentence);
                        }
                        case "task-finished" -> finished.complete(List.copyOf(sentences));
                        case "task-failed" -> {
                            String code = header.getString("error_code");
                            boolean retryable = code != null && switch (code) {
                                case "Throttling", "REQUEST_LIMIT_EXCEEDED", "TOO_MANY_REQUESTS" -> true;
                                default -> false;
                            };
                            fail(new AiProviderException("Paraformer task failed (" + code + ")", null, retryable, 0));
                        }
                        default -> throw new IllegalStateException("Unexpected Paraformer event");
                    }
                } catch (RuntimeException error) {
                    fail(error);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable error, Response response) {
                if (response == null) {
                    fail(new AiProviderException("Paraformer connection outcome unknown", error, false, 0));
                } else {
                    fail(AiProviderException.forStatus("Paraformer HTTP " + response.code(), error, response.code(), 0));
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                fail(new AiProviderException("Paraformer closed before task-finished (" + code + ")", null, false, 0));
            }

            private void fail(Throwable error) {
                started.completeExceptionally(error);
                finished.completeExceptionally(error);
            }
        });
        try (var pcm = Files.newInputStream(path)) {
            var payload = JSONObject.of("task_group", "audio", "task", "asr",
                    "function", "recognition", "model", AliyunSpeechTranscriber.MODEL,
                    "parameters", JSONObject.of("format", "pcm", "sample_rate", AsrSlices.SAMPLE_RATE,
                            "disfluency_removal_enabled", false));
            payload.put("input", new JSONObject());
            send(socket, command("run-task", id, payload));
            started.get(30, TimeUnit.SECONDS);
            byte[] packet = new byte[PACKET_BYTES];
            for (int count; (count = pcm.readNBytes(packet, 0, packet.length)) > 0;) {
                if (finished.isDone()) {
                    finished.get();
                    throw new IllegalStateException("Paraformer finished while PCM remained");
                }
                if (!socket.send(ByteString.of(packet, 0, count))) throw new IOException("ASR audio send failed");
                Thread.sleep(PACKET_INTERVAL_MS);
            }
            send(socket, command("finish-task", id, JSONObject.of("input", new JSONObject())));
            return finished.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw new AiProviderException("Paraformer transport failed", error.getCause(), false, 0);
        } catch (IOException | TimeoutException error) {
            throw new AiProviderException("Paraformer connection outcome unknown", error, false, 0);
        } finally {
            socket.cancel();
        }
    }

    static void appendSentence(List<TranscriptSegmentDraft> sentences, JSONObject sentence) {
        if (sentence == null) return;
        Long start = sentence.getLong("begin_time");
        Long end = sentence.getLong("end_time");
        String text = sentence.getString("text");
        // Final silence events can include timestamps without recognized speech.
        if (start != null && end != null && text != null && !text.isBlank()) {
            sentences.add(new TranscriptSegmentDraft(start, end, text));
        }
    }

    private static String command(String action, String id, JSONObject payload) {
        return JSONObject.of("header", JSONObject.of("action", action, "task_id", id, "streaming", "duplex"),
                "payload", payload).toJSONString();
    }

    private static void send(WebSocket socket, String message) throws IOException {
        if (!socket.send(message)) throw new IOException("ASR command send failed");
    }
}

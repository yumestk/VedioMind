package com.example.server.service.ai.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.*;

import okhttp3.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Direct HTTP makes all actual attempts visible to the same ledger; no SDK-internal retry. */
@Component
public class AliyunTextEmbeddingClient {
    public static final String MODEL = "text-embedding-v4";
    public static final int DIMENSION = 1024;
    public static final int MAX_BATCH_SIZE = 10;
    private static final OkHttpClient HTTP =
            new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
    private final String apiKey, baseUrl;
    private final AiCallLedger ledger;

    public AliyunTextEmbeddingClient(
            @Value("${ai.aliyun.api-key}") String apiKey,
            @Value("${ai.aliyun.embedding-base-url:https://dashscope.aliyuncs.com/api/v1}")
                    String baseUrl,
            AiCallLedger ledger) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.ledger = ledger;
    }

    public EmbeddingBatch embedDocuments(List<String> texts) {
        return embed(texts, "document", () -> {}, null);
    }

    public List<Double> embedQuery(String text) {
        return embedQuery(text, () -> {});
    }

    public List<Double> embedQuery(String text, Runnable beforeAttempt) {
        return embedQuery(text, beforeAttempt, null);
    }

    public List<Double> embedQuery(String text, Runnable beforeAttempt, Instant deadline) {
        if (text == null) throw new IllegalArgumentException("Query must not be null");
        return embed(List.of(text), "query", beforeAttempt, deadline).vectors().getFirst();
    }

    private EmbeddingBatch embed(
            List<String> texts, String type, Runnable beforeAttempt, Instant deadline) {
        if (texts == null
                || texts.isEmpty()
                || texts.size() > MAX_BATCH_SIZE
                || texts.stream().anyMatch(t -> t == null || t.isBlank()))
            throw new IllegalArgumentException(
                    "Embedding batch must contain 1 to 10 non-empty texts");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("ALIYUN_API_KEY is not configured");
        var body =
                JSONObject.of(
                        "model",
                        MODEL,
                        "input",
                        JSONObject.of("texts", texts),
                        "parameters",
                        JSONObject.of("text_type", type, "dimension", DIMENSION));
        var request =
                new Request.Builder()
                        .url(baseUrl + "/services/embeddings/text-embedding/text-embedding")
                        .header("Authorization", "Bearer " + apiKey)
                        .post(
                                RequestBody.create(
                                        body.toJSONString(), MediaType.parse("application/json")))
                        .build();
        var reply =
                ledger.exchange(
                        AiRateLimitService.ALIYUN_EMBEDDING,
                        "RETRIEVAL",
                        body,
                        beforeAttempt,
                        deadline,
                        effective -> {
                            var call = HTTP.newCall(request);
                            call.timeout()
                                    .timeout(
                                            AiRetryExecutor.remainingMillis(effective),
                                            TimeUnit.MILLISECONDS);
                            try (var binding = ledger.bind(call);
                                    var response = call.execute()) {
                                return new AiCallLedger.WireResponse(
                                        response.code(),
                                        response.body() == null ? "" : response.body().string(),
                                        DeepSeekChatClient.parseRetryAfterMillis(
                                                response.header("Retry-After"), Instant.now()));
                            }
                        });
        var json = JSON.parseObject(reply.body());
        var values =
                json.getJSONObject("output")
                        .getJSONArray("embeddings")
                        .toJavaList(JSONObject.class);
        if (values.size() != texts.size())
            throw new IllegalArgumentException("Incomplete embedding batch");
        values.sort(Comparator.comparingInt(v -> v.getIntValue("text_index")));
        List<List<Double>> vectors = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            var vector = value.getJSONArray("embedding").toJavaList(Double.class);
            if (value.getIntValue("text_index") != i
                    || vector.size() != DIMENSION
                    || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v)))
                throw new IllegalArgumentException("Invalid embedding result");
            vectors.add(List.copyOf(vector));
        }
        var usage = json.getJSONObject("usage");
        return new EmbeddingBatch(
                List.copyOf(vectors), usage == null ? 0 : usage.getIntValue("total_tokens"));
    }

    public record EmbeddingBatch(List<List<Double>> vectors, int totalTokens) {}
}

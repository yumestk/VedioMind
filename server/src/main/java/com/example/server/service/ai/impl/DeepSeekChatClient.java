package com.example.server.service.ai.impl;

import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.AiCallLedger;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);

    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();

    private final String apiKey;
    private final String baseUrl;
    private final AiCallLedger ledger;
    private final AiPromptBudget promptBudget;

    public DeepSeekChatClient(
            @Value("${ai.deepseek.api-key}") String apiKey,
            @Value("${ai.deepseek.base-url}") String baseUrl,
            AiCallLedger ledger,
            AiPromptBudget promptBudget) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.ledger = ledger;
        this.promptBudget = promptBudget;
    }

    public String chat(String systemPrompt, List<DeepSeekMessage> conversation) {
        return chatWithUsage(systemPrompt, conversation).content();
    }

    public ChatResult chatWithUsage(String systemPrompt, List<DeepSeekMessage> conversation) {
        return chatWithUsage(systemPrompt, conversation, () -> {});
    }

    public ChatResult chatWithinBudget(
            String systemPrompt, List<DeepSeekMessage> conversation, Runnable beforeFirstAttempt) {
        promptBudget.requireFits(systemPrompt, conversation);
        long estimated = promptBudget.estimate(systemPrompt, conversation);
        ChatResult result =
                send(
                        systemPrompt,
                        conversation,
                        beforeFirstAttempt,
                        promptBudget.outputTokens(),
                        null);
        log.info(
                "AI context budget estimatedInputTokens={} actualInputTokens={}"
                    + " actualOutputTokens={}",
                estimated,
                result.inputTokens(),
                result.outputTokens());
        return result;
    }

    public ChatResult chatWithUsage(
            String systemPrompt, List<DeepSeekMessage> conversation, Runnable beforeFirstAttempt) {
        return send(
                systemPrompt, conversation, beforeFirstAttempt, promptBudget.outputTokens(), null);
    }

    public ChatResult chatWithinBudget(
            String systemPrompt,
            List<DeepSeekMessage> conversation,
            Runnable beforeAttempt,
            Instant deadline) {
        promptBudget.requireFits(systemPrompt, conversation);
        return send(
                systemPrompt, conversation, beforeAttempt, promptBudget.outputTokens(), deadline);
    }

    private ChatResult send(
            String systemPrompt,
            List<DeepSeekMessage> conversation,
            Runnable beforeAttempt,
            int maxOutputTokens,
            Instant deadline) {
        if (systemPrompt == null
                || systemPrompt.isBlank()
                || conversation == null
                || conversation.isEmpty()) {
            throw new IllegalArgumentException("DeepSeek messages must not be empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");
        }

        for (DeepSeekMessage message : conversation) {
            if (message == null
                    || !Set.of("user", "assistant").contains(message.role())
                    || message.content() == null
                    || message.content().isBlank())
                throw new IllegalArgumentException("DeepSeek message is invalid");
        }
        JSONObject bodyJson = AiPromptBudget.request(systemPrompt, conversation, maxOutputTokens);

        Request request =
                new Request.Builder()
                        .url(baseUrl + "/chat/completions")
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(
                                RequestBody.create(
                                        bodyJson.toString(),
                                        MediaType.parse("application/json; charset=utf-8")))
                        .build();

        try {
            var response =
                    ledger.exchange(
                            AiRateLimitService.DEEPSEEK,
                            "GENERATION",
                            bodyJson,
                            beforeAttempt,
                            deadline,
                            effective -> {
                                var call = CLIENT.newCall(request);
                                call.timeout()
                                        .timeout(
                                                AiRetryExecutor.remainingMillis(effective),
                                                TimeUnit.MILLISECONDS);
                                try (var binding = ledger.bind(call);
                                        var reply = call.execute()) {
                                    return new AiCallLedger.WireResponse(
                                            reply.code(),
                                            reply.body() == null ? "" : reply.body().string(),
                                            parseRetryAfterMillis(
                                                    reply.header("Retry-After"), Instant.now()));
                                }
                            });
            JSONObject json = JSONObject.parseObject(response.body());
            JSONObject usage = json.getJSONObject("usage");
            Integer input = usage == null ? null : usage.getInteger("prompt_tokens");
            Integer output = usage == null ? null : usage.getInteger("completion_tokens");
            JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
            String finish = choice.getString("finish_reason");
            String content = choice.getJSONObject("message").getString("content");
            if ("length".equals(finish))
                throw new AiPromptBudget.PromptBudgetExceededException(
                        "AI output reached max-output-tokens");
            if (content == null || content.isBlank())
                throw new AiProviderException(
                        "Language model returned an empty response", null, false, 0);
            return new ChatResult(content, input, output);
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException
                    || failure instanceof AiProviderException) throw failure;
            throw new AiProviderException(
                    "Language model returned an invalid response", failure, false, 0);
        }
    }

    static long parseRetryAfterMillis(String value, Instant now) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value.trim()) * 1_000L);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt =
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant();
                return Math.max(0, retryAt.toEpochMilli() - now.toEpochMilli());
            } catch (DateTimeParseException invalidDate) {
                return 0;
            }
        }
    }

    public record ChatResult(String content, Integer inputTokens, Integer outputTokens) {}
}

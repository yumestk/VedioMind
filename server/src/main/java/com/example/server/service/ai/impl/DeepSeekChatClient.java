package com.example.server.service.ai.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.AiProviderException;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.example.server.service.ai.AiPromptBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class DeepSeekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatClient.class);

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    private final String apiKey;
    private final String baseUrl;
    private final AiRetryExecutor retryExecutor;
    private final AiPromptBudget promptBudget;

    public DeepSeekChatClient(
            @Value("${ai.deepseek.api-key}") String apiKey,
            @Value("${ai.deepseek.base-url}") String baseUrl,
            AiRetryExecutor retryExecutor, AiPromptBudget promptBudget
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.retryExecutor = retryExecutor;
        this.promptBudget = promptBudget;
    }

    public String chat(String systemPrompt, List<DeepSeekMessage> conversation) {
        return chatWithUsage(systemPrompt, conversation).content();
    }

    public ChatResult chatWithUsage(String systemPrompt, List<DeepSeekMessage> conversation) {
        return chatWithUsage(systemPrompt, conversation, () -> {});
    }

    public ChatResult chatWithinBudget(String systemPrompt, List<DeepSeekMessage> conversation,
                                       Runnable beforeFirstAttempt) {
        promptBudget.requireFits(systemPrompt, conversation);
        long estimated = promptBudget.estimate(systemPrompt, conversation);
        ChatResult result = send(systemPrompt, conversation, beforeFirstAttempt, promptBudget.outputTokens());
        log.info("AI context budget estimatedInputTokens={} actualInputTokens={} actualOutputTokens={}",
                estimated, result.inputTokens(), result.outputTokens());
        return result;
    }

    public ChatResult chatWithUsage(String systemPrompt, List<DeepSeekMessage> conversation, Runnable beforeFirstAttempt) {
        return send(systemPrompt, conversation, beforeFirstAttempt, null);
    }

    private ChatResult send(String systemPrompt, List<DeepSeekMessage> conversation,
                            Runnable beforeFirstAttempt, Integer maxOutputTokens) {
        return send(systemPrompt, conversation, beforeFirstAttempt, maxOutputTokens, null, attempt -> {});
    }

    public ChatResult chatWithinBudget(String systemPrompt, List<DeepSeekMessage> conversation,
                                       Runnable beforeAttempt, Instant deadline, Consumer<ChatAttempt> observer) {
        promptBudget.requireFits(systemPrompt, conversation);
        return send(systemPrompt, conversation, beforeAttempt, promptBudget.outputTokens(), deadline, observer);
    }

    private ChatResult send(String systemPrompt, List<DeepSeekMessage> conversation,
                            Runnable beforeAttempt, Integer maxOutputTokens, Instant deadline,
                            Consumer<ChatAttempt> observer) {
        if (systemPrompt == null || systemPrompt.isBlank() || conversation == null || conversation.isEmpty()) {
            throw new IllegalArgumentException("DeepSeek messages must not be empty");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");
        }

        JSONObject bodyJson = new JSONObject();
        bodyJson.put("model", "deepseek-v4-flash");
        bodyJson.put("stream", false);
        if (maxOutputTokens != null) bodyJson.put("max_tokens", maxOutputTokens);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", systemPrompt));
        for (DeepSeekMessage message : conversation) {
            if (message == null
                    || !Set.of("user", "assistant").contains(message.role())
                    || message.content() == null
                    || message.content().isBlank()) {
                throw new IllegalArgumentException("DeepSeek message is invalid");
            }
            messages.add(JSONObject.of("role", message.role(), "content", message.content()));
        }
        bodyJson.put("messages", messages);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(
                        bodyJson.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                ))
                .build();

        return retryExecutor.execute(AiRateLimitService.DEEPSEEK, beforeAttempt,
                () -> execute(request, maxOutputTokens != null, deadline, observer), deadline);
    }

    private ChatResult execute(Request request, boolean boundedOutput, Instant deadline, Consumer<ChatAttempt> observer) {
        long started = System.nanoTime();
        String raw = null, content = null, reasoning = null, finish = null, error = null;
        Integer input = null, output = null, status = null;
        try {
            var call = CLIENT.newCall(request);
            call.timeout().timeout(AiRetryExecutor.remainingMillis(deadline), TimeUnit.MILLISECONDS);
            try (Response response = call.execute()) {
                status = response.code();
                raw = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw AiProviderException.forStatus("Language model request failed: " + status,
                            null, status, parseRetryAfterMillis(response.header("Retry-After"), Instant.now()));
                }
                JSONObject json = JSONObject.parseObject(raw);
                JSONObject usage = json.getJSONObject("usage");
                input = usage == null ? null : usage.getInteger("prompt_tokens");
                output = usage == null ? null : usage.getInteger("completion_tokens");
                JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
                finish = choice.getString("finish_reason");
                content = choice.getJSONObject("message").getString("content");
                reasoning = choice.getJSONObject("message").getString("reasoning_content");
                if (boundedOutput && "length".equals(finish)) {
                    throw new AiPromptBudget.PromptBudgetExceededException("AI output reached max-output-tokens");
                }
                if (content == null || content.isBlank()) {
                    throw new AiProviderException("Language model returned an empty response", null, false, 0);
                }
                return new ChatResult(content, input, output);
            }
        } catch (IOException exception) {
            error = "Language model connection failed: " + exception.getClass().getSimpleName();
            throw new AiProviderException(error, exception, true, 0);
        } catch (RuntimeException exception) {
            error = exception.getMessage();
            if (exception instanceof AiProviderException || exception instanceof AiPromptBudget.PromptBudgetExceededException) throw exception;
            throw new AiProviderException("Language model returned an invalid response", exception, false, 0);
        } finally {
            observer.accept(new ChatAttempt(raw, content, reasoning, finish, input, output, status,
                    error, (System.nanoTime() - started) / 1_000_000));
        }
    }

    public record ChatAttempt(String rawResponse, String content, String reasoningContent, String finishReason,
                              Integer inputTokens, Integer outputTokens, Integer httpStatus, String error,
                              long elapsedMs) { }

    static long parseRetryAfterMillis(String value, Instant now) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value.trim()) * 1_000L);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(
                        value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME
                ).toInstant();
                return Math.max(0, retryAt.toEpochMilli() - now.toEpochMilli());
            } catch (DateTimeParseException invalidDate) {
                return 0;
            }
        }
    }

    public record ChatResult(String content, Integer inputTokens, Integer outputTokens) {
    }
}

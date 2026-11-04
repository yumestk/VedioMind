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

        JSONObject reply = exchange(bodyJson, beforeAttempt, deadline);
        JSONObject message =
                reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String content = message.getString("content");
        if (content == null
                || content.isBlank()
                || (message.getJSONArray("tool_calls") != null
                        && !message.getJSONArray("tool_calls").isEmpty()))
            throw new AiProviderException("Language model returned no text answer", null, false, 0);
        var usage = reply.getJSONObject("usage");
        return new ChatResult(
                content,
                usage == null ? null : usage.getInteger("prompt_tokens"),
                usage == null ? null : usage.getInteger("completion_tokens"));
    }

    /** The exact native message (including provider call IDs) is checkpointed and replayed. */
    public JSONObject tools(
            java.util.List<JSONObject> messages,
            java.util.List<JSONObject> tools,
            Runnable beforeAttempt,
            Instant deadline) {
        validateMessages(messages);
        var body =
                JSONObject.of(
                        "model",
                        AiPromptBudget.MODEL,
                        "stream",
                        false,
                        "thinking",
                        JSONObject.of("type", "disabled"),
                        "messages",
                        messages,
                        "tools",
                        tools);
        body.put("max_tokens", promptBudget.outputTokens());
        if (promptBudget.estimateRequest(body) > promptBudget.inputLimit())
            throw new AiPromptBudget.PromptBudgetExceededException("工具会话超过输入预算");
        JSONObject reply = exchange(body, beforeAttempt, deadline);
        JSONObject message =
                reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        if (!"assistant".equals(message.getString("role")))
            throw new AiProviderException("Invalid assistant role", null, false, 0);
        var calls = message.getJSONArray("tool_calls");
        if (calls == null || calls.isEmpty()) {
            if (message.getString("content") == null || message.getString("content").isBlank())
                throw new AiProviderException("Empty assistant response", null, false, 0);
        } else {
            var ids = new java.util.HashSet<String>();
            for (int i = 0; i < calls.size(); i++) {
                var call = calls.getJSONObject(i);
                String id = call.getString("id");
                var function = call.getJSONObject("function");
                if (id == null
                        || id.isBlank()
                        || id.length() > 200
                        || !ids.add(id)
                        || !"function".equals(call.getString("type"))
                        || function == null
                        || !(function.get("name") instanceof String name)
                        || name.isBlank()
                        || !(function.get("arguments") instanceof String))
                    throw new AiProviderException("Malformed native tool call", null, false, 0);
            }
        }
        return message;
    }

    static void validateMessages(java.util.List<JSONObject> messages) {
        if (messages == null
                || messages.size() < 2
                || !"system".equals(messages.getFirst().getString("role")))
            throw new IllegalArgumentException("Missing tool conversation");
        var pending = new java.util.LinkedHashSet<String>();
        var used = new java.util.HashSet<String>();
        for (var message : messages) {
            String role = message.getString("role");
            if ("tool".equals(role)) {
                if (!pending.remove(message.getString("tool_call_id"))
                        || !(message.get("content") instanceof String))
                    throw new IllegalArgumentException("Unpaired tool observation");
                continue;
            }
            if (!pending.isEmpty() || !Set.of("system", "user", "assistant").contains(role))
                throw new IllegalArgumentException("Unresolved tool calls");
            var calls = message.getJSONArray("tool_calls");
            if (calls != null && !calls.isEmpty()) {
                if (!"assistant".equals(role))
                    throw new IllegalArgumentException("Invalid tool caller");
                for (int i = 0; i < calls.size(); i++) {
                    String id = calls.getJSONObject(i).getString("id");
                    if (id == null || !used.add(id))
                        throw new IllegalArgumentException("Duplicate tool call ID");
                    pending.add(id);
                }
            } else if (!(message.get("content") instanceof String text) || text.isBlank())
                throw new IllegalArgumentException("Empty native message");
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Missing tool observation");
    }

    private JSONObject exchange(JSONObject body, Runnable beforeAttempt, Instant deadline) {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("DEEPSEEK_API_KEY is not configured");
        Request request =
                new Request.Builder()
                        .url(baseUrl + "/chat/completions")
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .addHeader("Content-Type", "application/json")
                        .post(
                                RequestBody.create(
                                        body.toJSONString(),
                                        MediaType.parse("application/json; charset=utf-8")))
                        .build();
        var response =
                ledger.exchange(
                        AiRateLimitService.DEEPSEEK,
                        "GENERATION",
                        body,
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
        try {
            var reply = JSONObject.parseObject(response.body());
            if ("length"
                    .equals(
                            reply.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getString("finish_reason")))
                throw new AiPromptBudget.PromptBudgetExceededException(
                        "AI output reached max-output-tokens");
            if (reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message") == null)
                throw new IllegalArgumentException("Missing assistant message");
            return reply;
        } catch (AiPromptBudget.PromptBudgetExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiProviderException(
                    "Language model returned an invalid response", e, false, 0);
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

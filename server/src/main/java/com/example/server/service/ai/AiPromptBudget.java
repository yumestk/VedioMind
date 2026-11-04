package com.example.server.service.ai;

import com.alibaba.fastjson2.JSON;
import com.example.server.service.ai.impl.DeepSeekMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Conservative estimates, NOT model-tokenizer counts. No dependency on mutable model aliases. */
@Component
public class AiPromptBudget {
    public static final String MODEL = "deepseek-v4-flash";
    private final int contextTokens;
    private final int outputTokens;
    private static final int SAFETY_TOKENS = 512;

    public AiPromptBudget(
            @Value("${ai.context.max-estimated-context-tokens:64000}") int contextTokens,
            @Value("${ai.context.max-output-tokens:8192}") int outputTokens) {
        if (outputTokens <= 0 || contextTokens - (long) outputTokens - SAFETY_TOKENS < 1024) {
            throw new IllegalArgumentException("AI context budget must leave at least 1024 estimated input tokens");
        }
        this.contextTokens = contextTokens;
        this.outputTokens = outputTokens;
    }

    public int inputLimit() { return contextTokens - outputTokens - SAFETY_TOKENS; }
    public int outputTokens() { return outputTokens; }

    public static long estimateText(String value) {
        // ponytail: one UTF-8 byte per estimated token deliberately overestimates ordinary text.
        // A matching tokenizer + chat template is needed for exact counts; aliases can change remotely.
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public long estimate(String system, List<DeepSeekMessage> messages) {
        return estimateRequest(request(system, messages, outputTokens));
    }

    /** Same JSON object as the HTTP client. Native tool schemas/calls/messages are counted when present. */
    public long estimateRequest(java.util.Map<String, ?> request) {
        Object messages = request.get("messages");
        int count = messages instanceof java.util.Collection<?> values ? values.size() : 0;
        return estimateText(JSON.toJSONString(request)) + 64L * count;
    }

    public static com.alibaba.fastjson2.JSONObject request(String system, List<DeepSeekMessage> conversation, Integer output) {
        var messages = new com.alibaba.fastjson2.JSONArray();
        messages.add(com.alibaba.fastjson2.JSONObject.of("role", "system", "content", system));
        conversation.forEach(m -> messages.add(com.alibaba.fastjson2.JSONObject.of("role", m.role(), "content", m.content())));
        var body = com.alibaba.fastjson2.JSONObject.of("model", MODEL, "stream", false, "messages", messages);
        if (output != null) body.put("max_tokens", output);
        return body;
    }

    public boolean fits(String system, List<DeepSeekMessage> messages) {
        return estimate(system, messages) <= inputLimit();
    }

    public void requireFits(String system, List<DeepSeekMessage> messages) {
        long estimated = estimate(system, messages);
        if (estimated > inputLimit()) {
            throw new PromptBudgetExceededException("AI input exceeds estimated token budget: "
                    + estimated + " > " + inputLimit());
        }
    }

    /** Deterministic input errors must never consume paid failure retries. */
    public static class PromptBudgetExceededException extends IllegalArgumentException {
        public PromptBudgetExceededException(String message) { super(message); }
    }
}

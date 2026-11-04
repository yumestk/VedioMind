package com.example.server.service.ai;

import com.example.server.service.ai.impl.DeepSeekMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AiPromptBudgetTest {
    @Test void countsEscapedWireBodyToolSchemasAndToolMessages() {
        var budget = new AiPromptBudget(4000, 512);
        var messages = List.of(new DeepSeekMessage("user", "\"quoted\"\n中文"));
        var request = AiPromptBudget.request("rules", messages, 512);
        assertThat(budget.estimate("rules", messages)).isEqualTo(budget.estimateRequest(request));
        long before = budget.estimateRequest(request);
        request.put("tools", List.of(com.alibaba.fastjson2.JSONObject.of("type", "function", "function",
                com.alibaba.fastjson2.JSONObject.of("name", "read_evidence", "description", "长工具描述".repeat(300)))));
        request.getJSONArray("messages").add(com.alibaba.fastjson2.JSONObject.of("role","tool","tool_call_id","call-1","content","工具原文".repeat(200)));
        assertThat(budget.estimateRequest(request)).isGreaterThan(before + 3000);
    }

    @Test
    void productionDefaultsReserve8192OutputWithinTheMeasured64kContextAndRemainConfigurable() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(AiPromptBudget.class); context.refresh();
            assertThat(context.getBean(AiPromptBudget.class).inputLimit()).isEqualTo(55296);
            assertThat(context.getBean(AiPromptBudget.class).outputTokens()).isEqualTo(8192);
        }
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",
                    java.util.Map.of("ai.context.max-estimated-context-tokens","48000","ai.context.max-output-tokens","4096")));
            context.register(AiPromptBudget.class); context.refresh();
            assertThat(context.getBean(AiPromptBudget.class).inputLimit()).isEqualTo(48000-4096-512);
            assertThat(context.getBean(AiPromptBudget.class).outputTokens()).isEqualTo(4096);
        }
    }

    @Test
    void reservesOutputAndSafetyAndCountsAllMessagesAndFormatting() {
        var budget = new AiPromptBudget(4000, 512);
        var messages = List.of(new DeepSeekMessage("user", "问题"), new DeepSeekMessage("assistant", "回答"));
        assertThat(budget.inputLimit()).isEqualTo(2976);
        assertThat(AiPromptBudget.estimateText("中A")).isEqualTo(4);
        assertThat(budget.estimate("系统", messages)).isGreaterThan(AiPromptBudget.estimateText("系统问题回答"));
        assertThat(budget.fits("系统", messages)).isTrue();
        assertThatThrownBy(() -> budget.requireFits("系统".repeat(1000), messages))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        assertThatThrownBy(() -> new AiPromptBudget(1024, 512)).isInstanceOf(IllegalArgumentException.class);
    }
}

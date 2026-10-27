package com.example.server.service.ai;

import com.example.server.service.ai.impl.DeepSeekMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AiPromptBudgetTest {
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

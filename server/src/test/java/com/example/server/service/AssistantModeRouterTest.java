package com.example.server.service;

import com.example.server.service.ai.impl.DeepSeekChatClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantModeRouterTest {

    private final DeepSeekChatClient chatClient = mock(DeepSeekChatClient.class);
    private final AssistantModeRouter router = new AssistantModeRouter(chatClient);

    @Test
    void routesExplicitModesWithoutCallingTheModel() {
        AssistantModeRouter.Decision decision = router.route("DEEP", "解释一下", true, false);

        assertThat(decision.mode()).isEqualTo("DEEP");
        assertThat(decision.routedBy()).isEqualTo("EXPLICIT");
        verify(chatClient, never()).chat(anyString(), any());
    }

    @Test
    void rejectsDeepModeForUrlVideos() {
        assertThatThrownBy(() -> router.route("DEEP", "整理视频", false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Study Mode");
    }

    @Test
    void recognizesDeepTasksAndAgentFollowUpsWithRules() {
        assertThat(router.route("AUTO", "整理整节课并生成自测题", true, false).mode())
                .isEqualTo("DEEP");
        assertThat(router.route("AUTO", "第二个知识点再解释一下", true, true).mode())
                .isEqualTo("QUICK");

        verify(chatClient, never()).chat(anyString(), any());
    }

    @Test
    void usesTheModelForAmbiguousStudyRequests() {
        when(chatClient.chat(anyString(), any())).thenReturn(
                "{\"mode\":\"DEEP\",\"confidence\":0.88,\"reason\":\"需要结构化产物\"}"
        );

        AssistantModeRouter.Decision decision = router.route("AUTO", "帮我学完这段内容", true, false);

        assertThat(decision.mode()).isEqualTo("DEEP");
        assertThat(decision.routedBy()).isEqualTo("MODEL");
    }

    @Test
    void fallsBackToQuickWhenModelRoutingFails() {
        when(chatClient.chat(anyString(), any())).thenThrow(new IllegalStateException("provider down"));

        AssistantModeRouter.Decision decision = router.route("AUTO", "帮我处理一下", true, false);

        assertThat(decision.mode()).isEqualTo("QUICK");
        assertThat(decision.routedBy()).isEqualTo("FALLBACK");
    }
}

package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.AiRateLimitService;
import com.example.server.service.ai.AiRetryExecutor;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekChatClientTest {

    @Test
    void parsesRetryAfterSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        assertThat(DeepSeekChatClient.parseRetryAfterMillis("2", now)).isEqualTo(2_000);
        assertThat(DeepSeekChatClient.parseRetryAfterMillis(
                "Tue, 13 Oct 2026 10:00:03 GMT", now
        )).isEqualTo(3_000);
        assertThat(DeepSeekChatClient.parseRetryAfterMillis("invalid", now)).isZero();
    }

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<JSONObject> sent = new AtomicReference<>();
    private final AiRateLimitService rates = mock(AiRateLimitService.class);

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsTheOutputReservationAndLogsProviderUsageAfterBudgetValidation() throws Exception {
        var client = client("stop");
        Runnable reserve = mock(Runnable.class);
        var result = client.chatWithinBudget("系统", List.of(new DeepSeekMessage("user", "问题")), reserve);
        assertThat(result.inputTokens()).isEqualTo(123);
        assertThat(result.outputTokens()).isEqualTo(20);
        assertThat(sent.get().getInteger("max_tokens")).isEqualTo(512);
        assertThat(sent.get().getJSONArray("messages")).hasSize(2);
        assertThat(sent.get().getJSONArray("messages").getJSONObject(0).getString("role")).isEqualTo("system");
        verify(reserve).run();
        verify(rates).requireProviderRequest(AiRateLimitService.DEEPSEEK);
        assertThat(requests).hasValue(1);
    }

    @Test
    void oversizedInputNeverAcquiresQuotaOrReservesAPaidCall() throws Exception {
        var client = client("stop");
        Runnable reserve = mock(Runnable.class);
        assertThatThrownBy(() -> client.chatWithinBudget("系统",
                List.of(new DeepSeekMessage("user", "长".repeat(3000))), reserve))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        assertThat(requests).hasValue(0);
        verifyNoInteractions(rates, reserve);
    }

    @Test
    void truncatedOutputFailsExplicitlyWithoutPayingForAnotherAttempt() throws Exception {
        var client = client("length");
        assertThatThrownBy(() -> client.chatWithinBudget("系统",
                List.of(new DeepSeekMessage("user", "问题")), () -> {}))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class)
                .hasMessageContaining("max-output-tokens");
        assertThat(requests).hasValue(1);
        verify(rates).requireProviderRequest(AiRateLimitService.DEEPSEEK);
        verifyNoMoreInteractions(rates);
    }

    @Test
    void truncatedResponsePreservesRawReasoningUsageAndFinishReason() throws Exception {
        var client = client("length");
        var observed = new AtomicReference<DeepSeekChatClient.ChatAttempt>();
        assertThatThrownBy(() -> client.chatWithinBudget("system", List.of(new DeepSeekMessage("user","q")),
                () -> {}, Instant.now().plusSeconds(1), observed::set))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        assertThat(observed.get().rawResponse()).contains("reasoning_content");
        assertThat(observed.get().reasoningContent()).isEqualTo("reasoning");
        assertThat(observed.get().finishReason()).isEqualTo("length");
        assertThat(observed.get().outputTokens()).isEqualTo(20);
    }

    @Test
    void httpCallCannotOverrunTheSharedDeadlineOrStartAnotherAttempt() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var client = new DeepSeekChatClient("test-key","http://127.0.0.1:"+server.getAddress().getPort(),
                new AiRetryExecutor(rates),new AiPromptBudget(4000,512));
        var observed = new AtomicReference<DeepSeekChatClient.ChatAttempt>();
        long start=System.nanoTime();
        assertThatThrownBy(() -> client.chatWithinBudget("system",List.of(new DeepSeekMessage("user","q")),
                () -> {},Instant.now().plusMillis(100),observed::set))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(450);
        assertThat(requests).hasValue(1);
        assertThat(observed.get().inputTokens()).isNull();
    }

    private DeepSeekChatClient client(String finishReason) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            sent.set(JSONObject.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"" + finishReason + "\","
                    + "\"message\":{\"content\":\"answer\",\"reasoning_content\":\"reasoning\"}}],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":20}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new DeepSeekChatClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                new AiRetryExecutor(rates), new AiPromptBudget(4000, 512));
    }
}

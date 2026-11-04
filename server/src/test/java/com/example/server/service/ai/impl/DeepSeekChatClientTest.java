package com.example.server.service.ai.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson2.JSONObject;
import com.example.server.service.ai.AiPromptBudget;
import com.example.server.service.ai.AiRateLimitService;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class DeepSeekChatClientTest {

    @Test
    void parsesRetryAfterSecondsAndHttpDate() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        assertThat(DeepSeekChatClient.parseRetryAfterMillis("2", now)).isEqualTo(2_000);
        assertThat(DeepSeekChatClient.parseRetryAfterMillis("Tue, 13 Oct 2026 10:00:03 GMT", now))
                .isEqualTo(3_000);
        assertThat(DeepSeekChatClient.parseRetryAfterMillis("invalid", now)).isZero();
    }

    @Test
    void nativeMessagesRequireRealCallIdPairingBeforeAnyTransport() {
        var initial =
                List.of(
                        JSONObject.of("role", "system", "content", "rules"),
                        JSONObject.of("role", "user", "content", "task"));
        var messages = new java.util.ArrayList<>(initial);
        var assistant =
                JSONObject.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        List.of(
                                JSONObject.of(
                                        "id",
                                        "provider-id",
                                        "type",
                                        "function",
                                        "function",
                                        JSONObject.of("name", "read_skill", "arguments", "{}"))));
        messages.add(assistant);
        assertThatThrownBy(() -> DeepSeekChatClient.validateMessages(messages))
                .isInstanceOf(IllegalArgumentException.class);
        messages.add(
                JSONObject.of("role", "tool", "tool_call_id", "fabricated-id", "content", "{}"));
        assertThatThrownBy(() -> DeepSeekChatClient.validateMessages(messages))
                .isInstanceOf(IllegalArgumentException.class);
        messages.getLast().put("tool_call_id", "provider-id");
        DeepSeekChatClient.validateMessages(messages);
        messages.add(assistant);
        messages.add(JSONObject.of("role", "tool", "tool_call_id", "provider-id", "content", "{}"));
        assertThatThrownBy(() -> DeepSeekChatClient.validateMessages(messages))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    private com.example.server.service.ai.AiCallLedger transportLedger() throws Exception {
        var ledger = mock(com.example.server.service.ai.AiCallLedger.class);
        when(ledger.bind(any())).thenReturn(() -> {});
        when(ledger.exchange(anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(
                        c -> {
                            rates.requireProviderRequest(c.getArgument(0));
                            ((Runnable) c.getArgument(3)).run();
                            Instant deadline = c.getArgument(4);
                            try {
                                return ((com.example.server.service.ai.AiCallLedger.Transport)
                                                c.getArgument(5))
                                        .send(deadline);
                            } catch (java.io.IOException unknown) {
                                throw new com.example.server.service.ai.StudyExecutionContext
                                        .GovernanceException("MODEL_OUTCOME_UNKNOWN", "unknown");
                            }
                        });
        return ledger;
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
        var result =
                client.chatWithinBudget("系统", List.of(new DeepSeekMessage("user", "问题")), reserve);
        assertThat(result.inputTokens()).isEqualTo(123);
        assertThat(result.outputTokens()).isEqualTo(20);
        assertThat(sent.get().getInteger("max_tokens")).isEqualTo(512);
        assertThat(sent.get().getJSONArray("messages")).hasSize(2);
        assertThat(sent.get().getJSONArray("messages").getJSONObject(0).getString("role"))
                .isEqualTo("system");
        verify(reserve).run();
        verify(rates).requireProviderRequest(AiRateLimitService.DEEPSEEK);
        assertThat(requests).hasValue(1);
    }

    @Test
    void oversizedInputNeverAcquiresQuotaOrReservesAPaidCall() throws Exception {
        var client = client("stop");
        Runnable reserve = mock(Runnable.class);
        assertThatThrownBy(
                        () ->
                                client.chatWithinBudget(
                                        "系统",
                                        List.of(new DeepSeekMessage("user", "长".repeat(3000))),
                                        reserve))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class);
        assertThat(requests).hasValue(0);
        verifyNoInteractions(rates, reserve);
    }

    @Test
    void truncatedOutputFailsExplicitlyWithoutPayingForAnotherAttempt() throws Exception {
        var client = client("length");
        assertThatThrownBy(
                        () ->
                                client.chatWithinBudget(
                                        "系统", List.of(new DeepSeekMessage("user", "问题")), () -> {}))
                .isInstanceOf(AiPromptBudget.PromptBudgetExceededException.class)
                .hasMessageContaining("max-output-tokens");
        assertThat(requests).hasValue(1);
        verify(rates).requireProviderRequest(AiRateLimitService.DEEPSEEK);
        verifyNoMoreInteractions(rates);
    }

    @Test
    void httpCallCannotOverrunTheSharedDeadlineOrStartAnotherAttempt() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    requests.incrementAndGet();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        var client =
                new DeepSeekChatClient(
                        "test-key",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        transportLedger(),
                        new AiPromptBudget(4000, 512));
        long start = System.nanoTime();
        assertThatThrownBy(
                        () ->
                                client.chatWithinBudget(
                                        "system",
                                        List.of(new DeepSeekMessage("user", "q")),
                                        () -> {},
                                        Instant.now().plusMillis(100)))
                .isInstanceOf(
                        com.example.server.service.ai.StudyExecutionContext.GovernanceException
                                .class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(450);
        assertThat(requests).hasValue(1);
    }

    private DeepSeekChatClient client(String finishReason) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/chat/completions",
                exchange -> {
                    requests.incrementAndGet();
                    sent.set(
                            JSONObject.parseObject(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)));
                    byte[] body =
                            ("{\"choices\":[{\"finish_reason\":\""
                                            + finishReason
                                            + "\",\"message\":{\"content\":\"answer\",\"reasoning_content\":\"reasoning\"}}],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":20}}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        return new DeepSeekChatClient(
                "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                transportLedger(),
                new AiPromptBudget(4000, 512));
    }
}

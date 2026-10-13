package com.example.server.service.ai.impl;

import org.junit.jupiter.api.Test;

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
}

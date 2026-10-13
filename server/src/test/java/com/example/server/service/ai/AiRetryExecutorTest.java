package com.example.server.service.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AiRetryExecutorTest {

    private final AiRateLimitService rateLimitService = mock(AiRateLimitService.class);
    private final List<Duration> delays = new ArrayList<>();
    private final AiRetryExecutor executor = new AiRetryExecutor(
            rateLimitService, delays::add, () -> 100
    );

    @Test
    void retriesTransientFailuresThreeTimesWithProviderPermitsAndBackoff() {
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute(AiRateLimitService.DEEPSEEK, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new AiProviderException("temporary", null, true, 0);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(delays).containsExactly(Duration.ofMillis(1_100), Duration.ofMillis(2_100));
        verify(rateLimitService, times(3)).requireProviderRequest(AiRateLimitService.DEEPSEEK);
    }

    @Test
    void doesNotRetryPermanentFailures() {
        assertThatThrownBy(() -> executor.execute(AiRateLimitService.DEEPSEEK, () -> {
            throw new AiProviderException("unauthorized", null, false, 0);
        })).isInstanceOf(AiProviderException.class).hasMessage("unauthorized");

        assertThat(delays).isEmpty();
        verify(rateLimitService).requireProviderRequest(AiRateLimitService.DEEPSEEK);
    }

    @Test
    void classifiesOnlyTransientProviderStatusesAsRetryable() {
        assertThat(AiProviderException.forStatus("network", null, -1, 0).isRetryable()).isTrue();
        assertThat(AiProviderException.forStatus("timeout", null, 408, 0).isRetryable()).isTrue();
        assertThat(AiProviderException.forStatus("limited", null, 429, 0).isRetryable()).isTrue();
        assertThat(AiProviderException.forStatus("server", null, 503, 0).isRetryable()).isTrue();
        assertThat(AiProviderException.forStatus("unauthorized", null, 401, 0).isRetryable()).isFalse();
        assertThat(AiProviderException.forStatus("bad request", null, 400, 0).isRetryable()).isFalse();
    }

    @Test
    void prefersRetryAfterButCapsTheDelay() {
        AtomicInteger attempts = new AtomicInteger();

        executor.execute(AiRateLimitService.DEEPSEEK, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AiProviderException("limited", null, true, 60_000);
            }
            return "ok";
        });

        assertThat(delays).containsExactly(Duration.ofMillis(AiRetryExecutor.MAX_DELAY_MILLIS));
    }
}

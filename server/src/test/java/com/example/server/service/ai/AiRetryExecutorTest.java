package com.example.server.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
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
    void exposesTheProductionConstructorToSpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiRateLimitService.class, () -> rateLimitService);
            context.register(AiRetryExecutor.class);
            context.refresh();

            assertThat(context.getBean(AiRetryExecutor.class)).isNotNull();
        }
    }

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
    void quotaRejectionDoesNotReserveBudgetOrCallProvider() {
        AtomicInteger budget = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        doThrow(new AiRateLimitService.AiRateLimitExceededException("busy", 60))
                .when(rateLimitService).requireProviderRequest(AiRateLimitService.DEEPSEEK);

        assertThatThrownBy(() -> executor.execute(AiRateLimitService.DEEPSEEK,
                budget::incrementAndGet, calls::incrementAndGet))
                .isInstanceOf(AiRateLimitService.AiRateLimitExceededException.class);

        assertThat(budget).hasValue(0);
        assertThat(calls).hasValue(0);
        assertThat(delays).isEmpty();
    }

    @Test
    void retainsBudgetForARealCallWhenTheFollowingRetryCannotGetQuota() {
        AtomicInteger budget = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        doNothing().doThrow(new AiRateLimitService.AiRateLimitExceededException("busy", 60))
                .when(rateLimitService).requireProviderRequest(AiRateLimitService.DEEPSEEK);

        assertThatThrownBy(() -> executor.execute(AiRateLimitService.DEEPSEEK,
                budget::incrementAndGet, () -> {
                    calls.incrementAndGet();
                    throw new AiProviderException("network", null, true, 0);
                })).isInstanceOf(AiRateLimitService.AiRateLimitExceededException.class);

        assertThat(budget).hasValue(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void reservesOneLogicalBudgetAcrossLocalProviderRetries() {
        AtomicInteger budget = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        executor.execute(AiRateLimitService.DEEPSEEK, budget::incrementAndGet, () -> {
            if (calls.incrementAndGet() < 3) throw new AiProviderException("network", null, true, 0);
            return "ok";
        });
        assertThat(budget).hasValue(1);
        assertThat(calls).hasValue(3);
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

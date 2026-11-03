package com.example.server.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Component
public class AiRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiRetryExecutor.class);
    static final int MAX_ATTEMPTS = 3;
    static final long MAX_DELAY_MILLIS = 30_000;

    private final AiRateLimitService rateLimitService;
    private final Consumer<Duration> sleeper;
    private final LongSupplier jitterMillis;

    @Autowired
    public AiRetryExecutor(AiRateLimitService rateLimitService) {
        this(rateLimitService, AiRetryExecutor::sleep, () -> ThreadLocalRandom.current().nextLong(251));
    }

    AiRetryExecutor(
            AiRateLimitService rateLimitService,
            Consumer<Duration> sleeper,
            LongSupplier jitterMillis
    ) {
        this.rateLimitService = rateLimitService;
        this.sleeper = sleeper;
        this.jitterMillis = jitterMillis;
    }

    public <T> T execute(String provider, Supplier<T> operation) {
        return execute(provider, () -> {}, operation);
    }

    public <T> T execute(String provider, Runnable beforeAttempt, Supplier<T> operation) {
        return execute(provider, beforeAttempt, operation, null);
    }

    public <T> T execute(String provider, Runnable beforeAttempt, Supplier<T> operation, Instant deadline) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            remainingMillis(deadline);
            rateLimitService.requireProviderRequest(provider);
            remainingMillis(deadline);
            beforeAttempt.run();
            try {
                return operation.get();
            } catch (AiProviderException exception) {
                if (!exception.isRetryable() || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                long delayMillis = retryDelayMillis(exception, attempt);
                log.warn("AI provider {} failed on attempt {}/{}; retrying in {} ms: {}",
                        provider, attempt, MAX_ATTEMPTS, delayMillis, exception.getMessage());
                if (deadline != null && delayMillis >= remainingMillis(deadline)) {
                    throw new AiPromptBudget.PromptBudgetExceededException("Task deadline leaves no time for another attempt");
                }
                sleeper.accept(Duration.ofMillis(delayMillis));
            }
        }
        throw new IllegalStateException("Unreachable AI retry state");
    }

    public static long remainingMillis(Instant deadline) {
        if (Thread.currentThread().isInterrupted()) throw new AiPromptBudget.PromptBudgetExceededException("Task interrupted");
        if (deadline == null) return 300_000;
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) throw new AiPromptBudget.PromptBudgetExceededException("Task execution deadline exceeded");
        return remaining;
    }

    private long retryDelayMillis(AiProviderException exception, int attempt) {
        long requestedDelay = exception.retryAfterMillis() > 0
                ? exception.retryAfterMillis()
                : 1_000L << (attempt - 1);
        return Math.min(MAX_DELAY_MILLIS, requestedDelay + Math.max(0, jitterMillis.getAsLong()));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry AI request", exception);
        }
    }
}

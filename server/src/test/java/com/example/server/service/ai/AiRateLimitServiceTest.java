package com.example.server.service.ai;

import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiRateLimitServiceTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final AiRateLimitService service = new AiRateLimitService(
            redissonClient, 5, 30, 30, 6, 60
    );

    @Test
    void consumesGlobalAndUserPermitsForAnAcceptedRequest() {
        RRateLimiter user = limiter("rate:ai:v1:user:5:7", true);
        RRateLimiter global = limiter("rate:ai:v1:global:30", true);

        service.requireRequest(7L);

        verify(global).trySetRate(RateType.OVERALL, 30, 1, RateIntervalUnit.MINUTES);
        verify(global).tryAcquire();
        verify(user).trySetRate(RateType.OVERALL, 5, 1, RateIntervalUnit.MINUTES);
        verify(user).tryAcquire();
    }

    @Test
    void rejectsARequestWhenTheUserBucketIsEmpty() {
        limiter("rate:ai:v1:user:5:7", false);

        assertThatThrownBy(() -> service.requireRequest(7L))
                .isInstanceOf(AiRateLimitService.AiRateLimitExceededException.class)
                .hasMessageContaining("你的 AI 请求");
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(redissonClient.getRateLimiter("rate:ai:v1:user:5:7"))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.requireRequest(7L))
                .isInstanceOf(AiRateLimitService.AiRateLimitUnavailableException.class);
    }

    private RRateLimiter limiter(String key, boolean acquired) {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter(key)).thenReturn(limiter);
        when(limiter.tryAcquire()).thenReturn(acquired);
        return limiter;
    }
}

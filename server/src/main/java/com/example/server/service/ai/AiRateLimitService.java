package com.example.server.service.ai;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRateLimitService {

    public static final String DEEPSEEK = "deepseek";
    public static final String ALIYUN_ASR = "aliyun-asr";
    public static final String ALIYUN_EMBEDDING = "aliyun-embedding";
    static final long RETRY_AFTER_SECONDS = 60;

    private final RedissonClient redissonClient;
    private final long userRequestsPerMinute;
    private final long globalRequestsPerMinute;
    private final long deepSeekRequestsPerMinute;
    private final long aliyunAsrRequestsPerMinute;
    private final long aliyunEmbeddingRequestsPerMinute;

    public AiRateLimitService(
            RedissonClient redissonClient,
            @Value("${ai.rate-limit.user-per-minute:5}") long userRequestsPerMinute,
            @Value("${ai.rate-limit.global-per-minute:30}") long globalRequestsPerMinute,
            @Value("${ai.rate-limit.deepseek-per-minute:30}") long deepSeekRequestsPerMinute,
            @Value("${ai.rate-limit.aliyun-asr-per-minute:6}") long aliyunAsrRequestsPerMinute,
            @Value("${ai.rate-limit.aliyun-embedding-per-minute:60}") long aliyunEmbeddingRequestsPerMinute
    ) {
        this.redissonClient = redissonClient;
        this.userRequestsPerMinute = positive(userRequestsPerMinute);
        this.globalRequestsPerMinute = positive(globalRequestsPerMinute);
        this.deepSeekRequestsPerMinute = positive(deepSeekRequestsPerMinute);
        this.aliyunAsrRequestsPerMinute = positive(aliyunAsrRequestsPerMinute);
        this.aliyunEmbeddingRequestsPerMinute = positive(aliyunEmbeddingRequestsPerMinute);
    }

    public void requireRequest(Long userId) {
        acquire("rate:ai:v1:user:" + userRequestsPerMinute + ":"
                        + (userId == null ? "anonymous" : userId),
                userRequestsPerMinute, "你的 AI 请求过于频繁，请稍后重试");
        acquire("rate:ai:v1:global:" + globalRequestsPerMinute,
                globalRequestsPerMinute, "AI 请求过于频繁，请稍后重试");
    }

    public void requireProviderRequest(String provider) {
        long rate = switch (provider) {
            case DEEPSEEK -> deepSeekRequestsPerMinute;
            case ALIYUN_ASR -> aliyunAsrRequestsPerMinute;
            case ALIYUN_EMBEDDING -> aliyunEmbeddingRequestsPerMinute;
            default -> throw new IllegalArgumentException("Unknown AI provider: " + provider);
        };
        acquire("rate:ai:v1:provider:" + provider + ":" + rate,
                rate, "AI 服务繁忙，请稍后重试");
    }

    private void acquire(String key, long permitsPerMinute, String message) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(key);
            limiter.trySetRate(RateType.OVERALL, permitsPerMinute, 1, RateIntervalUnit.MINUTES);
            if (!limiter.tryAcquire()) {
                throw new AiRateLimitExceededException(message, RETRY_AFTER_SECONDS);
            }
        } catch (AiRateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiRateLimitUnavailableException("AI 限流服务暂时不可用，请稍后重试", exception);
        }
    }

    private static long positive(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("AI rate limits must be positive");
        }
        return value;
    }

    public static class AiRateLimitExceededException extends RuntimeException {
        private final long retryAfterSeconds;

        public AiRateLimitExceededException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public static class AiRateLimitUnavailableException extends RuntimeException {
        public AiRateLimitUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

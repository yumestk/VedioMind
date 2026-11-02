package com.example.server.security;

import org.redisson.api.*;
import org.springframework.stereotype.Service;

@Service
public class AuthRateLimits {
    private final RedissonClient redis;

    public AuthRateLimits(RedissonClient redis) {
        this.redis = redis;
    }

    public void require(String action, String ip, String account) {
        acquire("ip:" + action + ":" + AuthCrypto.hash(ip), action.equals("register") ? 5 : 60);
        if (account != null)
            acquire("account:" + AuthCrypto.hash(account.toLowerCase(java.util.Locale.ROOT)), 10);
    }

    private void acquire(String key, long rate) {
        try {
            RRateLimiter limiter = redis.getRateLimiter("rate:auth:v1:" + key);
            limiter.trySetRate(RateType.OVERALL, rate, 1, RateIntervalUnit.MINUTES);
            limiter.expire(java.time.Duration.ofMinutes(5));
            if (!limiter.tryAcquire())
                throw new AuthException(429, "AUTH_RATE_LIMITED", "操作过于频繁，请稍后重试");
        } catch (AuthException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AuthException(503, "AUTH_STORE_UNAVAILABLE", "登录服务暂时不可用，请稍后重试");
        }
    }
}

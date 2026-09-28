package com.ledgerline.gateway.ratelimit;

import com.ledgerline.gateway.ratelimit.RateLimitProperties.TierLimit;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * One token bucket per merchant in Redis, shared by every gateway instance. All the logic is in
 * {@code token_bucket.lua}; this class only passes the arguments and reads the result.
 */
@Component
public class RedisRateLimiter {

    // Spring sends EVALSHA and falls back to EVAL once if Redis doesn't have the script cached yet.
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("ratelimit/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Takes one token from the merchant's bucket. Throws if Redis can't be reached. */
    public RateLimitDecision tryAcquire(long merchantId, TierLimit limit) {
        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(TOKEN_BUCKET, List.of("ratelimit:merchant:" + merchantId),
                String.valueOf(limit.ratePerSecond()), String.valueOf(limit.burst()));
        return new RateLimitDecision(result.get(0) == 1L, result.get(1), result.get(2));
    }
}

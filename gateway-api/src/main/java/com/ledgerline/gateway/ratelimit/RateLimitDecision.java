package com.ledgerline.gateway.ratelimit;

/**
 * @param remaining        whole tokens left after this request
 * @param retryAfterMillis when rejected, how long until one token is available; 0 when allowed
 */
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterMillis) {
}

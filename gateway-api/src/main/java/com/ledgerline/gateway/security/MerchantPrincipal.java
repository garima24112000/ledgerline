package com.ledgerline.gateway.security;

import com.ledgerline.gateway.merchant.RateLimitTier;

/**
 * The authenticated merchant behind an API key. Controllers get it with {@code @AuthenticationPrincipal}.
 * The tier comes from the same row as the key, so rate limiting needs no extra query.
 */
public record MerchantPrincipal(long merchantId, String name, RateLimitTier tier) {
}

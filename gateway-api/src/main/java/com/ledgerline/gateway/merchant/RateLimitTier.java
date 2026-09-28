package com.ledgerline.gateway.merchant;

/** Request-rate tier of a merchant. The limits for each tier are in {@code gateway.rate-limit.tiers}. */
public enum RateLimitTier {
    FREE,
    PRO
}

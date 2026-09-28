package com.ledgerline.gateway.ratelimit;

import com.ledgerline.gateway.merchant.RateLimitTier;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token-bucket limits per merchant tier. Checked when the app starts: a tier without a limit
 * would otherwise only fail on that tier's first request.
 *
 * @param enabled false turns the filter into a no-op
 * @param tiers   one limit for every {@link RateLimitTier}
 */
@ConfigurationProperties("gateway.rate-limit")
public record RateLimitProperties(boolean enabled, Map<RateLimitTier, TierLimit> tiers) {

    public RateLimitProperties {
        tiers = tiers == null ? Map.of() : Map.copyOf(tiers);
        for (RateLimitTier tier : RateLimitTier.values()) {
            if (!tiers.containsKey(tier)) {
                throw new IllegalArgumentException("gateway.rate-limit.tiers." + tier + " is missing");
            }
        }
    }

    public TierLimit limitFor(RateLimitTier tier) {
        return tiers.get(tier);
    }

    /**
     * @param ratePerSecond tokens added per second: the sustained request rate
     * @param burst         bucket capacity: how many requests may arrive at once after a quiet period
     */
    public record TierLimit(int ratePerSecond, int burst) {

        public TierLimit {
            if (ratePerSecond <= 0 || burst <= 0) {
                throw new IllegalArgumentException("rate-per-second and burst must be positive");
            }
        }
    }
}

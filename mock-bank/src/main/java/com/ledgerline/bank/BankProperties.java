package com.ledgerline.bank;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the fake bank behaves. Every charge lands in exactly one bucket: approve, decline or
 * timeout, so the three rates must add up to 1. Checked at startup, so a typo fails fast.
 *
 * @param timeoutSleep    how long a "timeout" charge sleeps; must be longer than the caller's read timeout
 * @param timeoutsEnabled false (env {@code BANK_TIMEOUTS_ENABLED=false}, for load tests) answers the
 *                        timeout bucket's charges on time instead: same approve:decline odds, normal
 *                        latency. The {@code tok_timeout} card token still times out.
 */
@ConfigurationProperties("bank")
public record BankProperties(
        double approveRate,
        double declineRate,
        double timeoutRate,
        Duration latencyMin,
        Duration latencyMax,
        Duration timeoutSleep,
        @DefaultValue("true") boolean timeoutsEnabled) {

    public BankProperties {
        if (approveRate < 0 || declineRate < 0 || timeoutRate < 0) {
            throw new IllegalArgumentException("bank rates must not be negative");
        }
        if (Math.abs(approveRate + declineRate + timeoutRate - 1.0) > 1e-9) {
            throw new IllegalArgumentException("bank.approve-rate + decline-rate + timeout-rate must be 1, was "
                    + (approveRate + declineRate + timeoutRate));
        }
        if (latencyMin.isNegative() || latencyMin.compareTo(latencyMax) > 0) {
            throw new IllegalArgumentException("bank.latency-min must be >= 0 and <= latency-max");
        }
    }
}

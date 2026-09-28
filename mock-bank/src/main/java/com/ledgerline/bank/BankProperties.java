package com.ledgerline.bank;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the fake bank behaves. Every charge lands in exactly one bucket: approve, decline or
 * timeout, so the three rates must add up to 1. Checked at startup, so a typo fails fast.
 *
 * @param timeoutSleep how long a "timeout" charge sleeps; must be longer than the caller's read timeout
 */
@ConfigurationProperties("bank")
public record BankProperties(
        double approveRate,
        double declineRate,
        double timeoutRate,
        Duration latencyMin,
        Duration latencyMax,
        Duration timeoutSleep) {

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

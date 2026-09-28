package com.ledgerline.gateway.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param lockTtl how long a request holds its key before another request (a client retry or the
 *                reconciler) may take over. Must be longer than the slowest request, which is
 *                bounded by the bank read timeout.
 */
@ConfigurationProperties("gateway.idempotency")
public record IdempotencyProperties(Duration lockTtl) {
}

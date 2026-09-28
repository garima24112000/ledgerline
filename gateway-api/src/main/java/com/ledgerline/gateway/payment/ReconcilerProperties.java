package com.ledgerline.gateway.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled   turn the schedule off (tests call {@link PaymentReconciler#reconcileOnce()} directly)
 * @param interval  pause between runs
 * @param minAge    leave payments alone until they have been unresolved this long
 * @param batchSize payments per run
 */
@ConfigurationProperties("gateway.reconciler")
public record ReconcilerProperties(boolean enabled, Duration interval, Duration minAge, int batchSize) {
}

package com.ledgerline.gateway.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox lag as two gauges: {@code outbox_unpublished_events} and
 * {@code outbox_oldest_unpublished_age_seconds}. A healthy relay keeps both near zero; a growing age
 * means events are stuck (Kafka down, or a poison row).
 *
 * <p>Recomputed on a schedule and cached, rather than queried on every scrape, so a slow or down
 * database never stalls {@code /actuator/prometheus}. Every replica reports the same numbers, so
 * dashboards take the {@code max}.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository repository;
    private final AtomicReference<OutboxLag> lag = new AtomicReference<>(OutboxLag.NONE);

    public OutboxMetrics(OutboxRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        Gauge.builder("outbox.unpublished.events", lag, l -> l.get().unpublished())
                .description("Outbox rows not yet published to Kafka")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.unpublished.age", lag, l -> l.get().oldestAgeSeconds())
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox row; 0 when there is none")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${gateway.outbox.lag-refresh}")
    public void refresh() {
        try {
            lag.set(repository.lag());
        } catch (RuntimeException e) {
            log.warn("Could not compute outbox lag; keeping the last value", e);
        }
    }
}

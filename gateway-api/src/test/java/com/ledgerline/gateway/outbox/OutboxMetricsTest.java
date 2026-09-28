package com.ledgerline.gateway.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class OutboxMetricsTest {

    @Mock
    private OutboxRepository repository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new OutboxMetrics(repository, meterRegistry);
    }

    @Test
    void gaugesAreZeroBeforeTheFirstRefresh() {
        assertThat(unpublished()).isZero();
        assertThat(oldestAge()).isZero();
    }

    @Test
    void refreshPublishesTheCurrentLag() {
        when(repository.lag()).thenReturn(new OutboxLag(42, 7.5));

        metrics.refresh();

        assertThat(unpublished()).isEqualTo(42);
        assertThat(oldestAge()).isEqualTo(7.5);
    }

    @Test
    void failedRefreshKeepsTheLastValue() {
        when(repository.lag())
                .thenReturn(new OutboxLag(3, 1.0))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        metrics.refresh();
        metrics.refresh();

        assertThat(unpublished()).isEqualTo(3);
        assertThat(oldestAge()).isEqualTo(1.0);
    }

    private double unpublished() {
        return meterRegistry.get("outbox.unpublished.events").gauge().value();
    }

    private double oldestAge() {
        return meterRegistry.get("outbox.oldest.unpublished.age").gauge().value();
    }
}

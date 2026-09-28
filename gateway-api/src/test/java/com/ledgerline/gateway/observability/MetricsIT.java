package com.ledgerline.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerline.gateway.AbstractGatewayIT;
import com.ledgerline.gateway.outbox.OutboxMetrics;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The metrics the Grafana dashboard reads are exported, with the tags it groups by. */
class MetricsIT extends AbstractGatewayIT {

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Test
    void paymentPathIsTimedWithHistograms() {
        stubCharge("APPROVED", 0);
        assertThat(createPayment(createMerchant(), UUID.randomUUID().toString(), 49_900, "tok_visa")
                .getStatusCode().value()).isEqualTo(201);

        String scrape = scrape();

        // _bucket lines exist only when percentile histograms are published.
        assertThat(line(scrape, "payment_create_seconds_bucket{", "outcome=\"captured\"")).isNotNull();
        assertThat(line(scrape, "ledger_post_seconds_bucket{", "entry_type=\"CAPTURE\"")).isNotNull();
        assertThat(line(scrape, "bank_call_seconds_bucket{", "operation=\"charge\"", "outcome=\"approved\"")).isNotNull();
        assertThat(line(scrape, "http_server_requests_seconds_bucket{", "uri=\"/v1/payments\"", "method=\"POST\""))
                .isNotNull();
        assertThat(line(scrape, "hikaricp_connections_active{")).isNotNull();
    }

    @Test
    void outboxLagReportsUnpublishedRowsAndTheOldestAge() {
        long merchantId = createMerchant().id();
        jdbc.sql("""
                        INSERT INTO outbox_events (id, merchant_id, aggregate_id, event_type, payload, created_at)
                        VALUES (?, ?, ?, 'payment.captured', '{"amount": 100}', now() - interval '2 minutes')""")
                .params(UUID.randomUUID(), merchantId, UUID.randomUUID())
                .update();

        outboxMetrics.refresh();
        String scrape = scrape();

        assertThat(value(scrape, "outbox_unpublished_events{")).isGreaterThanOrEqualTo(1);
        // Other tests' rows may be older still; this one guarantees at least two minutes.
        assertThat(value(scrape, "outbox_oldest_unpublished_age_seconds{")).isGreaterThanOrEqualTo(120);
    }

    private String scrape() {
        return rest.getForObject("/actuator/prometheus", String.class);
    }

    /** The first sample line starting with {@code prefix} and containing every label. */
    private static String line(String scrape, String prefix, String... labels) {
        return scrape.lines()
                .filter(l -> l.startsWith(prefix))
                .filter(l -> Arrays.stream(labels).allMatch(l::contains))
                .findFirst()
                .orElse(null);
    }

    private static double value(String scrape, String prefix) {
        String line = line(scrape, prefix);
        assertThat(line).as("sample %s", prefix).isNotNull();
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }
}

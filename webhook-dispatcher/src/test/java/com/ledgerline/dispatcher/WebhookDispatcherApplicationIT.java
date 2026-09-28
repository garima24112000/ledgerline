package com.ledgerline.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WebhookDispatcherApplicationIT extends AbstractDispatcherIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusMetricsAreExposed() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes", "dlq_size");
    }

    @Test
    void openApiDocumentsTheReplayEndpoint() {
        String docs = rest.getForObject("/v3/api-docs", String.class);

        assertThat(docs).contains("/admin/dlq/replay", "\"AdminBasic\"");
    }
}

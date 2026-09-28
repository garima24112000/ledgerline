package com.ledgerline.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GatewayApiApplicationIT extends AbstractGatewayIT {

    @Autowired
    private Flyway flyway;

    @Test
    void flywayAppliesAllMigrations() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
    }

    @Test
    void healthIsUpIncludingDatabase() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusMetricsAreExposed() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("hikaricp_connections");
    }

    @Test
    void openApiDocsAreServed() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Ledgerline Gateway API");
    }

    @Test
    void openApiDocumentsSecuritySchemesAndIdempotencyKey() {
        String docs = rest.getForObject("/v3/api-docs", String.class);

        assertThat(docs).contains("\"ApiKey\"", "\"X-Api-Key\"", "\"AdminBasic\"", "\"ServiceToken\"");
        assertThat(docs).contains("\"Idempotency-Key\"", "Idempotent-Replayed", "\"ApiError\"");
    }
}

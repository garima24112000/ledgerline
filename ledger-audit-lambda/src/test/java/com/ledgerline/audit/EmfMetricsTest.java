package com.ledgerline.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class EmfMetricsTest {

    @Test
    void producesAnEmbeddedMetricFormatDocument() throws Exception {
        ObjectMapper json = new ObjectMapper();

        JsonNode line = json.readTree(EmfMetrics.auditFailures(json, 1_790_000_000_000L, "fn", 3));

        JsonNode directive = line.get("_aws").get("CloudWatchMetrics").get(0);
        assertThat(line.get("_aws").get("Timestamp").asLong()).isEqualTo(1_790_000_000_000L);
        assertThat(directive.get("Namespace").asText()).isEqualTo("Ledgerline");
        assertThat(directive.get("Dimensions").get(0).get(0).asText()).isEqualTo("FunctionName");
        assertThat(directive.get("Metrics").get(0).get("Name").asText()).isEqualTo("LedgerAuditFailures");
        assertThat(directive.get("Metrics").get(0).get("Unit").asText()).isEqualTo("Count");
        // Every dimension and metric named in the directive must be a top-level member.
        assertThat(line.get("FunctionName").asText()).isEqualTo("fn");
        assertThat(line.get("LedgerAuditFailures").asInt()).isEqualTo(3);
    }
}

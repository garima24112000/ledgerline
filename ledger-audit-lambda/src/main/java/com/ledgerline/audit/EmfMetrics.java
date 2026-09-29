package com.ledgerline.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CloudWatch Embedded Metric Format: one JSON log line that CloudWatch Logs turns into the custom
 * metric Ledgerline/LedgerAuditFailures. No cloudwatch:PutMetricData call, so the function needs no
 * CloudWatch permission and no extra network call; the line is also a searchable log entry.
 */
final class EmfMetrics {

    static final String NAMESPACE = "Ledgerline";
    static final String METRIC = "LedgerAuditFailures";
    static final String DIMENSION = "FunctionName";

    private EmfMetrics() {
    }

    static String auditFailures(ObjectMapper json, long timestampMillis, String functionName, int failures) {
        Map<String, Object> metric = Map.of("Name", METRIC, "Unit", "Count");
        Map<String, Object> directive = Map.of(
                "Namespace", NAMESPACE,
                "Dimensions", List.of(List.of(DIMENSION)),
                "Metrics", List.of(metric));
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("_aws", Map.of("Timestamp", timestampMillis, "CloudWatchMetrics", List.of(directive)));
        line.put(DIMENSION, functionName);
        line.put(METRIC, failures);
        try {
            return json.writeValueAsString(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.ledgerline.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.ledgerline.gateway.AbstractGatewayIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;

/**
 * Logs are JSON with the payment and trace ids as fields, and the trace id is the one sent on to the bank.
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT extends AbstractGatewayIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void bankTimeoutIsLoggedAsJsonWithPaymentIdAndTheTraceIdSentToTheBank(CapturedOutput output) throws IOException {
        stubCharge("APPROVED", 2_000); // past the 1s read timeout used in tests
        ResponseEntity<String> response = createPayment(createMerchant(), UUID.randomUUID().toString(), 49_900, "tok_visa");
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        String paymentId = objectMapper.readTree(response.getBody()).get("id").asText();

        JsonNode logLine = output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(this::parse)
                .filter(json -> json.path("message").asText().startsWith("No answer from the bank"))
                .filter(json -> paymentId.equals(json.path("paymentId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no JSON log line for the timeout of " + paymentId));

        assertThat(logLine.path("level").asText()).isEqualTo("WARN");
        assertThat(logLine.path("app").asText()).isEqualTo("gateway-api");
        String traceId = logLine.path("traceId").asText();
        assertThat(traceId).matches("[0-9a-f]{32}");

        // W3C traceparent: 00-<traceId>-<spanId>-<flags>
        ServeEvent charge = bank.getAllServeEvents().stream()
                .filter(event -> event.getRequest().getUrl().equals("/charge"))
                .findFirst()
                .orElseThrow();
        String traceparent = charge.getRequest().getHeader("traceparent");
        assertThat(traceparent).isNotNull();
        assertThat(traceparent.split("-")[1]).isEqualTo(traceId);
    }

    private JsonNode parse(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (IOException e) {
            throw new UncheckedIOException("log line is not JSON: " + line, e);
        }
    }
}

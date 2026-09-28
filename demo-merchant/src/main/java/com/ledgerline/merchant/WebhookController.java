package com.ledgerline.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Webhooks", description = "Receives Ledgerline payment events")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final DemoMerchantProperties properties;
    private final ObjectMapper objectMapper;
    /** Event ids already processed. A real merchant would use a unique constraint in its database. */
    private final Cache<String, Boolean> processed = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(1_000_000)
            .build();

    public WebhookController(DemoMerchantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Schema(description = "Acknowledgement")
    public record Ack(@Schema(example = "processed", allowableValues = {"processed", "duplicate", "rejected", "failed"})
                      String status) {
    }

    /**
     * The body is taken as a raw String: the signature covers the exact bytes sent, so it must be
     * checked before (and independently of) any JSON parsing and re-serialization.
     */
    @PostMapping("/webhooks")
    @Operation(summary = "Receive a webhook",
            description = "Verifies the signature, ignores events already processed, and logs the event.")
    @ApiResponse(responseCode = "200", description = "Processed, or a duplicate of one already processed")
    @ApiResponse(responseCode = "400", description = "Not a Ledgerline event")
    @ApiResponse(responseCode = "401", description = "Missing, wrong or expired signature")
    @ApiResponse(responseCode = "500", description = "Simulated failure (FAIL_RATE); the dispatcher will retry")
    public ResponseEntity<Ack> receive(
            @RequestBody String body,
            @Parameter(description = "t=<unix seconds>,v1=<hex HMAC-SHA256 of \"<t>.<body>\">")
            @RequestHeader(value = "X-Ledgerline-Signature", required = false) String signature,
            @RequestHeader(value = "X-Ledgerline-Event-Id", required = false) String eventIdHeader) {
        try {
            return handle(body, signature, eventIdHeader);
        } finally {
            MDC.remove("eventId");
            MDC.remove("paymentId");
        }
    }

    private ResponseEntity<Ack> handle(String body, String signature, String eventIdHeader) {
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (IOException e) {
            return answer(HttpStatus.BAD_REQUEST, "rejected");
        }
        String eventId = event.path("id").asText(null);
        long merchantId = event.path("merchantId").asLong();
        if (eventId == null || (eventIdHeader != null && !eventIdHeader.equals(eventId))) {
            return answer(HttpStatus.BAD_REQUEST, "rejected");
        }
        MDC.put("eventId", eventId);

        // merchantId from the (still unverified) body only picks which key to check with:
        // without that merchant's secret, nobody can produce a signature that passes.
        String secret = properties.secrets().get(merchantId);
        if (secret == null
                || !SignatureVerifier.isValid(signature, body, secret, Instant.now(), properties.signatureTolerance())) {
            log.warn("Rejected webhook {} with an invalid signature", eventId);
            return answer(HttpStatus.UNAUTHORIZED, "rejected");
        }
        // Only now: an unverified body could put any id in our logs. Refund events name it paymentId.
        JsonNode data = event.path("data");
        String paymentId = data.hasNonNull("paymentId") ? data.get("paymentId").asText() : data.path("id").asText(null);
        if (paymentId != null) {
            MDC.put("paymentId", paymentId);
        }

        // Before recording the event as processed, so the retry is processed for real.
        if (ThreadLocalRandom.current().nextDouble() < properties.failRate()) {
            log.info("Simulating a failure for {} (FAIL_RATE={})", eventId, properties.failRate());
            return answer(HttpStatus.INTERNAL_SERVER_ERROR, "failed");
        }

        if (processed.asMap().putIfAbsent(eventId, Boolean.TRUE) != null) {
            log.info("Duplicate {} ignored", eventId);
            return answer(HttpStatus.OK, "duplicate");
        }
        log.info("Received {} {} for merchant {}: {}", event.path("type").asText(), eventId, merchantId, body);
        return answer(HttpStatus.OK, "processed");
    }

    private static ResponseEntity<Ack> answer(HttpStatus status, String result) {
        return ResponseEntity.status(status).body(new Ack(result));
    }
}

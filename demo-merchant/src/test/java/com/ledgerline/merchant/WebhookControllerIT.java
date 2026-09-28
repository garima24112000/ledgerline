package com.ledgerline.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookControllerIT {

    private static final String CHAI_POINT_SECRET = "whsec_demo_chaipoint"; // merchant 1 in application.yml

    @Autowired
    private TestRestTemplate rest;

    @Test
    void validWebhookIsProcessedAndADuplicateIsNotProcessedAgain(CapturedOutput output) {
        String eventId = UUID.randomUUID().toString();
        String body = event(eventId, 1);

        ResponseEntity<String> first = send(body, eventId, sign(CHAI_POINT_SECRET, Instant.now(), body));
        ResponseEntity<String> second = send(body, eventId, sign(CHAI_POINT_SECRET, Instant.now(), body));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("processed");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("duplicate");
        assertThat(output.getOut().split("Received payment.captured " + eventId, -1)).hasSize(2); // logged once
    }

    @Test
    void wrongSecretIsRejected() {
        String eventId = UUID.randomUUID().toString();
        String body = event(eventId, 1);

        assertThat(send(body, eventId, sign("whsec_wrong", Instant.now(), body)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signatureFromAnotherMerchantsSecretIsRejected() {
        String eventId = UUID.randomUUID().toString();
        String body = event(eventId, 2); // claims to be merchant 2, signed with merchant 1's secret

        assertThat(send(body, eventId, sign(CHAI_POINT_SECRET, Instant.now(), body)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void staleSignatureIsRejected() {
        String eventId = UUID.randomUUID().toString();
        String body = event(eventId, 1);

        assertThat(send(body, eventId, sign(CHAI_POINT_SECRET, Instant.now().minusSeconds(600), body)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingSignatureIsRejected() {
        String eventId = UUID.randomUUID().toString();

        assertThat(send(event(eventId, 1), eventId, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventIdHeaderMustMatchTheSignedBody() {
        String body = event(UUID.randomUUID().toString(), 1);

        assertThat(send(body, "evt_other", sign(CHAI_POINT_SECRET, Instant.now(), body)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "demo-merchant.fail-rate=1.0")
    class WhenFailRateIsOne {

        @Autowired
        private TestRestTemplate flakyRest;

        @Test
        void validWebhookGets500AndIsNotRecordedAsProcessed(CapturedOutput output) {
            String eventId = UUID.randomUUID().toString();
            String body = event(eventId, 1);

            ResponseEntity<String> response = send(flakyRest, body, eventId, sign(CHAI_POINT_SECRET, Instant.now(), body));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(output.getOut()).doesNotContain("Received payment.captured " + eventId);
        }
    }

    private ResponseEntity<String> send(String body, String eventId, String signature) {
        return send(rest, body, eventId, signature);
    }

    private static ResponseEntity<String> send(TestRestTemplate client, String body, String eventId, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Ledgerline-Event-Id", eventId);
        if (signature != null) {
            headers.set("X-Ledgerline-Signature", signature);
        }
        return client.postForEntity("/webhooks", new HttpEntity<>(body, headers), String.class);
    }

    private static String event(String eventId, long merchantId) {
        return """
                {"id":"%s","type":"payment.captured","merchantId":%d,"createdAt":"2026-09-28T10:00:00Z","data":{"amount":100}}"""
                .formatted(eventId, merchantId);
    }

    private static String sign(String secret, Instant at, String body) {
        long t = at.getEpochSecond();
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(SignatureVerifier.hmacSha256(secret, t + "." + body));
    }
}

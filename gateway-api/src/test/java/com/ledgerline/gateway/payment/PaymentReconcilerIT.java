package com.ledgerline.gateway.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.AbstractGatewayIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Bank timeouts: the gateway's read timeout is 1s in tests, and the stubbed bank answers after 1.5s. */
class PaymentReconcilerIT extends AbstractGatewayIT {

    private static final int SLOWER_THAN_READ_TIMEOUT = 1_500;

    @Autowired
    private PaymentReconciler reconciler;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void timeoutMakesPaymentUnknownAndReconcilerCapturesIt() throws Exception {
        stubCharge("APPROVED", SLOWER_THAN_READ_TIMEOUT);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> timedOut = createPayment(merchant, key, 10_000, "tok_visa");

        assertThat(timedOut.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID paymentId = UUID.fromString(json(timedOut).get("id").asText());
        assertThat(status(paymentId)).isEqualTo("UNKNOWN");
        assertThat(journalEntries(paymentId, "CAPTURE")).isZero();

        // The bank did charge the card; the reconciler asks and records the capture.
        stubLookup(paymentId, "APPROVED");
        reconciler.reconcileOnce();

        assertThat(status(paymentId)).isEqualTo("CAPTURED");
        assertThat(journalEntries(paymentId, "CAPTURE")).isEqualTo(1);
        assertThat(balance(merchant.payableAccountId())).isEqualTo(9_800);

        // The key was completed too: a late client retry gets the final answer without a new charge.
        ResponseEntity<String> retry = createPayment(merchant, key, 10_000, "tok_visa");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json(retry).get("status").asText()).isEqualTo("CAPTURED");
        bank.verify(exactly(1), postRequestedFor(urlEqualTo("/charge")));
    }

    @Test
    void chargeTheBankNeverReceivedIsFailed() throws Exception {
        stubCharge("APPROVED", SLOWER_THAN_READ_TIMEOUT);
        TestMerchant merchant = createMerchant();
        UUID paymentId = UUID.fromString(json(createPayment(merchant, UUID.randomUUID().toString(), 10_000, "tok_visa"))
                .get("id").asText());

        reconciler.reconcileOnce(); // no lookup stub: WireMock answers 404

        assertThat(status(paymentId)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT decline_reason FROM payments WHERE id = ?").param(paymentId).query(String.class).single())
                .isEqualTo("not_received_by_bank");
        assertThat(journalEntries(paymentId, "CAPTURE")).isZero();
    }

    @Test
    void clientRetryAfterTimeoutRedrivesTheSamePayment() throws Exception {
        stubCharge("APPROVED", SLOWER_THAN_READ_TIMEOUT);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();
        UUID paymentId = UUID.fromString(json(createPayment(merchant, key, 10_000, "tok_visa")).get("id").asText());

        stubCharge("APPROVED", 0); // the bank is fast again
        ResponseEntity<String> retry = createPayment(merchant, key, 10_000, "tok_visa");

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(retry).get("id").asText()).isEqualTo(paymentId.toString());
        assertThat(json(retry).get("status").asText()).isEqualTo("CAPTURED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments WHERE merchant_id = ?").param(merchant.id()).query(Long.class).single())
                .isEqualTo(1);
        // Both calls carried the same paymentId, which is what makes the bank's own idempotency work.
        bank.verify(exactly(2), postRequestedFor(urlEqualTo("/charge"))
                .withRequestBody(matchingJsonPath("$.paymentId", equalTo(paymentId.toString()))));
        assertThat(journalEntries(paymentId, "CAPTURE")).isEqualTo(1);
    }

    @Test
    void bankDownDuringReconciliationLeavesPaymentUnknownAndRetryable() throws Exception {
        stubCharge("APPROVED", SLOWER_THAN_READ_TIMEOUT);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();
        UUID paymentId = UUID.fromString(json(createPayment(merchant, key, 10_000, "tok_visa")).get("id").asText());
        bank.stubFor(get(urlEqualTo("/charges/" + paymentId)).willReturn(aResponse().withStatus(503)));

        reconciler.reconcileOnce();

        assertThat(status(paymentId)).isEqualTo("UNKNOWN");
        // The reconciler released the key again, so the next run (or a client retry) can proceed.
        stubLookup(paymentId, "DECLINED");
        reconciler.reconcileOnce();
        assertThat(status(paymentId)).isEqualTo("FAILED");
    }

    private String status(UUID paymentId) {
        return jdbc.sql("SELECT status FROM payments WHERE id = ?").param(paymentId).query(String.class).single();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }
}

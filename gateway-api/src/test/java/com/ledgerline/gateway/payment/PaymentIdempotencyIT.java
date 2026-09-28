package com.ledgerline.gateway.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.AbstractGatewayIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentIdempotencyIT extends AbstractGatewayIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sameKeyTwiceChargesOnceAndReturnsIdenticalResponses() throws Exception {
        stubCharge("APPROVED", 0);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = createPayment(merchant, key, 49_900, "tok_visa");
        ResponseEntity<String> second = createPayment(merchant, key, 49_900, "tok_visa");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().containsKey("Idempotent-Replayed")).isFalse();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody()); // byte for byte

        bank.verify(exactly(1), postRequestedFor(urlEqualTo("/charge")));
        UUID paymentId = UUID.fromString(json(first).get("id").asText());
        assertThat(json(first).get("status").asText()).isEqualTo("CAPTURED");
        assertThat(journalEntries(paymentId, "CAPTURE")).isEqualTo(1);
        assertThat(balance(merchant.payableAccountId())).isEqualTo(49_900 - 998);
    }

    @Test
    void sameKeyWithDifferentBodyIs422() throws Exception {
        stubCharge("APPROVED", 0);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();
        createPayment(merchant, key, 49_900, "tok_visa");

        ResponseEntity<String> reused = createPayment(merchant, key, 50_000, "tok_visa");

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(reused).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        bank.verify(exactly(1), postRequestedFor(urlEqualTo("/charge")));
    }

    @Test
    void sameBodyWithDifferentJsonFormattingIsStillAReplay() {
        stubCharge("APPROVED", 0);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();
        createPayment(merchant, key, 100, "tok_visa");

        ResponseEntity<String> reordered = postJson("/v1/payments", merchant, key, """
                {  "merchantOrderId":"order-1", "cardToken":"tok_visa",
                   "currency":"INR", "amount":100 }""");

        assertThat(reordered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reordered.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void twentyConcurrentRequestsWithOneKeyProduceExactlyOneCharge() throws Exception {
        stubCharge("APPROVED", 300); // keep the first request in flight while the others arrive
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();

        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return createPayment(merchant, key, 25_000, "tok_visa");
                }));
            }
            start.countDown();
        }
        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get());
        }

        List<ResponseEntity<String>> created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).toList();
        List<ResponseEntity<String>> conflicts = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).toList();
        assertThat(created.size() + conflicts.size()).isEqualTo(20);
        assertThat(created.stream().filter(r -> !r.getHeaders().containsKey("Idempotent-Replayed"))).hasSize(1);
        assertThat(created.stream().map(ResponseEntity::getBody).distinct()).hasSize(1);
        assertThat(conflicts).allSatisfy(r -> assertThat(json(r).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_KEY_IN_USE"));

        bank.verify(exactly(1), postRequestedFor(urlEqualTo("/charge")));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments WHERE merchant_id = ?").param(merchant.id()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM journal_entries j JOIN payments p ON p.id = j.payment_id
                        WHERE p.merchant_id = ?""").param(merchant.id()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void declineIsStoredAndReplayedWithoutLedgerEntry() throws Exception {
        stubCharge("DECLINED", 0);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = createPayment(merchant, key, 5_000, "tok_visa");
        ResponseEntity<String> replay = createPayment(merchant, key, 5_000, "tok_visa");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(first).get("status").asText()).isEqualTo("FAILED");
        assertThat(json(first).get("declineReason").asText()).isEqualTo("insufficient_funds");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(journalEntries(UUID.fromString(json(first).get("id").asText()), "CAPTURE")).isZero();
        bank.verify(exactly(1), postRequestedFor(urlEqualTo("/charge")));
    }

    @Test
    void missingIdempotencyKeyIs400() {
        ResponseEntity<String> response = postJson("/v1/payments", createMerchant(), null, """
                {"amount": 100, "currency": "INR", "cardToken": "tok_visa", "merchantOrderId": "o"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/error/code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void invalidBodyIs400WithFieldErrors() {
        ResponseEntity<String> response = postJson("/v1/payments", createMerchant(), "k", """
                {"amount": -1, "currency": "inr", "merchantOrderId": "o"}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/error/code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(json(response).at("/error/message").asText()).contains("amount", "cardToken", "currency");
    }

    @Test
    void unsupportedCurrencyDoesNotBurnTheKey() {
        stubCharge("APPROVED", 0);
        TestMerchant merchant = createMerchant();
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> usd = postJson("/v1/payments", merchant, key, """
                {"amount": 100, "currency": "USD", "cardToken": "tok_visa", "merchantOrderId": "o"}""");
        ResponseEntity<String> inr = createPayment(merchant, key, 100, "tok_visa");

        assertThat(usd.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(usd).at("/error/code").asText()).isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(inr.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.getBody(), e);
        }
    }
}

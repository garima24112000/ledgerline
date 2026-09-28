package com.ledgerline.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.AbstractGatewayIT;
import com.ledgerline.gateway.ledger.LedgerService;
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

class RefundIT extends AbstractGatewayIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LedgerService ledgerService;

    @Test
    void partialRefundsAddUpToAFullRefundThatReturnsTheWholeFee() throws Exception {
        TestMerchant merchant = createMerchant();
        UUID paymentId = capturedPayment(merchant, 9_999); // fee 199, merchant credited 9,800
        long feesBefore = platformFeesBalance();

        ResponseEntity<String> first = refund(merchant, paymentId, UUID.randomUUID().toString(), 4_999);
        ResponseEntity<String> second = refund(merchant, paymentId, UUID.randomUUID().toString(), 5_000);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(first).get("paymentRefundedAmount").asLong()).isEqualTo(4_999);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(second).get("paymentRefundedAmount").asLong()).isEqualTo(9_999);

        assertThat(balance(merchant.payableAccountId())).isZero(); // everything this payment credited is gone
        assertThat(platformFeesBalance() - feesBefore).isEqualTo(-199); // and the whole fee went back
        assertThat(journalEntries(paymentId, "REFUND")).isEqualTo(2);
        assertThat(ledgerService.verify().consistent()).isTrue();
    }

    @Test
    void overRefundIsRejectedWithoutTouchingTheLedgerAndFreesTheKey() throws Exception {
        TestMerchant merchant = createMerchant();
        UUID paymentId = capturedPayment(merchant, 10_000);
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> tooMuch = refund(merchant, paymentId, key, 10_001);

        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(tooMuch).at("/error/code").asText()).isEqualTo("REFUND_EXCEEDS_CAPTURED");
        assertThat(journalEntries(paymentId, "REFUND")).isZero();
        assertThat(balance(merchant.payableAccountId())).isEqualTo(9_800);

        // Nothing happened, so the same key works for the corrected request.
        assertThat(refund(merchant, paymentId, key, 10_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void refundCannotExceedWhatIsLeftAfterEarlierRefunds() throws Exception {
        TestMerchant merchant = createMerchant();
        UUID paymentId = capturedPayment(merchant, 10_000);
        refund(merchant, paymentId, UUID.randomUUID().toString(), 6_000);

        ResponseEntity<String> rest = refund(merchant, paymentId, UUID.randomUUID().toString(), 4_001);

        assertThat(rest.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(json(rest).at("/error/message").asText()).contains("refundable amount of 4000");
    }

    @Test
    void concurrentRefundsCannotOverRefund() throws Exception {
        TestMerchant merchant = createMerchant();
        UUID paymentId = capturedPayment(merchant, 10_000);

        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return refund(merchant, paymentId, UUID.randomUUID().toString(), 6_000);
                }));
            }
            start.countDown();
        }
        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            statuses.add((HttpStatus) future.get().getStatusCode());
        }

        assertThat(statuses).containsOnlyOnce(HttpStatus.CREATED);
        assertThat(statuses).filteredOn(s -> s == HttpStatus.UNPROCESSABLE_ENTITY).hasSize(4);
        assertThat(jdbc.sql("SELECT refunded_amount FROM payments WHERE id = ?").param(paymentId).query(Long.class).single())
                .isEqualTo(6_000);
        assertThat(journalEntries(paymentId, "REFUND")).isEqualTo(1);
    }

    @Test
    void refundIsIdempotent() throws Exception {
        TestMerchant merchant = createMerchant();
        UUID paymentId = capturedPayment(merchant, 10_000);
        String key = UUID.randomUUID().toString();

        ResponseEntity<String> first = refund(merchant, paymentId, key, 1_000);
        ResponseEntity<String> again = refund(merchant, paymentId, key, 1_000);

        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(journalEntries(paymentId, "REFUND")).isEqualTo(1);
    }

    @Test
    void failedPaymentCannotBeRefunded() throws Exception {
        stubCharge("DECLINED", 0);
        TestMerchant merchant = createMerchant();
        UUID paymentId = UUID.fromString(json(createPayment(merchant, UUID.randomUUID().toString(), 10_000, "tok_visa"))
                .get("id").asText());

        ResponseEntity<String> response = refund(merchant, paymentId, UUID.randomUUID().toString(), 100);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).at("/error/code").asText()).isEqualTo("PAYMENT_NOT_REFUNDABLE");
    }

    @Test
    void anotherMerchantsPaymentIsNotFound() throws Exception {
        TestMerchant owner = createMerchant();
        UUID paymentId = capturedPayment(owner, 10_000);

        ResponseEntity<String> response = refund(createMerchant(), paymentId, UUID.randomUUID().toString(), 100);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void databaseRejectsOverRefundEvenWithoutTheJavaCheck() {
        TestMerchant merchant = createMerchant();
        UUID paymentId = insertPayment(merchant.id(), 10_000, PaymentStatus.CAPTURED, java.time.Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE payments SET refunded_amount = 10001 WHERE id = ?").param(paymentId).update())
                .hasMessageContaining("payments_check");
    }

    private UUID capturedPayment(TestMerchant merchant, long amount) throws Exception {
        stubCharge("APPROVED", 0);
        ResponseEntity<String> response = createPayment(merchant, UUID.randomUUID().toString(), amount, "tok_visa");
        assertThat(json(response).get("status").asText()).isEqualTo("CAPTURED");
        return UUID.fromString(json(response).get("id").asText());
    }

    private ResponseEntity<String> refund(TestMerchant merchant, UUID paymentId, String key, long amount) {
        return postJson("/v1/payments/" + paymentId + "/refunds", merchant, key, "{\"amount\": " + amount + "}");
    }

    private long platformFeesBalance() {
        return jdbc.sql("SELECT balance FROM accounts WHERE owner_type = 'PLATFORM' AND type = 'PLATFORM_FEES'")
                .query(Long.class).single();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }
}

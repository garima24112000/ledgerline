package com.ledgerline.gateway.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.AbstractGatewayIT;
import com.ledgerline.gateway.idempotency.IdempotencyService;
import com.ledgerline.gateway.payment.BankResult;
import com.ledgerline.gateway.payment.PaymentTransitions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Every payment state change and its outbox event commit or roll back together. The spy only
 * matters for the forced-failure test; everywhere else it calls the real method. (It gives this
 * class its own Spring context, a price paid once.)
 */
class OutboxTransactionIT extends AbstractGatewayIT {

    @SpyBean
    private IdempotencyService idempotencyService;

    @Autowired
    private PaymentTransitions transitions;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private ObjectMapper objectMapper;

    record Row(long merchantId, String eventType, String payload, Object publishedAt, int attempts) {
    }

    @Test
    void captureWritesTheLedgerEntryAndPaymentCapturedTogether() throws Exception {
        TestMerchant merchant = createMerchant();
        stubCharge("APPROVED", 0);

        UUID paymentId = paymentId(createPayment(merchant, "k-" + UUID.randomUUID(), 10_000, "tok_visa"));

        assertThat(journalEntries(paymentId, "CAPTURE")).isEqualTo(1);
        List<Row> rows = outboxRows(paymentId);
        assertThat(rows).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.merchantId()).isEqualTo(merchant.id());
        assertThat(row.eventType()).isEqualTo("payment.captured");
        assertThat(row.publishedAt()).isNull();
        assertThat(row.attempts()).isZero();
        JsonNode data = objectMapper.readTree(row.payload());
        assertThat(data.get("id").asText()).isEqualTo(paymentId.toString());
        assertThat(data.get("status").asText()).isEqualTo("CAPTURED");
        assertThat(data.get("amount").asLong()).isEqualTo(10_000);
    }

    @Test
    void declineWritesPaymentFailed() throws Exception {
        TestMerchant merchant = createMerchant();
        stubCharge("DECLINED", 0);

        UUID paymentId = paymentId(createPayment(merchant, "k-" + UUID.randomUUID(), 10_000, "tok_visa"));

        assertThat(journalEntries(paymentId, "CAPTURE")).isZero();
        assertThat(outboxRows(paymentId)).singleElement().satisfies(row -> {
            assertThat(row.eventType()).isEqualTo("payment.failed");
            assertThat(objectMapper.readTree(row.payload()).get("declineReason").asText()).isEqualTo("insufficient_funds");
        });
    }

    @Test
    void refundWritesRefundCreated() throws Exception {
        TestMerchant merchant = createMerchant();
        stubCharge("APPROVED", 0);
        UUID paymentId = paymentId(createPayment(merchant, "k-" + UUID.randomUUID(), 10_000, "tok_visa"));

        ResponseEntity<String> refund = postJson("/v1/payments/" + paymentId + "/refunds", merchant,
                "r-" + UUID.randomUUID(), "{\"amount\": 4000}");
        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID refundId = UUID.fromString(objectMapper.readTree(refund.getBody()).get("id").asText());

        assertThat(outboxRows(refundId)).singleElement().satisfies(row -> {
            assertThat(row.eventType()).isEqualTo("refund.created");
            JsonNode data = objectMapper.readTree(row.payload());
            assertThat(data.get("paymentId").asText()).isEqualTo(paymentId.toString());
            assertThat(data.get("amount").asLong()).isEqualTo(4_000);
        });
    }

    /**
     * The capture writes the payment, the ledger entry and the outbox row, then fails on the last
     * step (completing the idempotency key). All of it must roll back.
     */
    @Test
    void failureMidTransactionRollsBackLedgerAndOutboxTogether() {
        TestMerchant merchant = createMerchant();
        UUID paymentId = insertPayment(merchant.id());
        long payableBefore = balance(merchant.payableAccountId());
        doThrow(new IllegalStateException("simulated crash"))
                .when(idempotencyService).complete(eq(merchant.id()), anyString(), any(), any());

        assertThatThrownBy(() -> transitions.recordBankResult(paymentId, BankResult.approved("bnk_1")))
                .hasMessage("simulated crash");

        assertThat(jdbc.sql("SELECT status FROM payments WHERE id = ?").param(paymentId).query(String.class).single())
                .isEqualTo("PENDING");
        assertThat(journalEntries(paymentId, "CAPTURE")).isZero();
        assertThat(balance(merchant.payableAccountId())).isEqualTo(payableBefore);
        assertThat(outboxRows(paymentId)).isEmpty();
    }

    @Test
    void appendingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outboxService.append(1, UUID.randomUUID(), EventType.PAYMENT_CAPTURED, "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private UUID paymentId(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(response.getBody()).get("id").asText());
    }

    private List<Row> outboxRows(UUID aggregateId) {
        return jdbc.sql("""
                        SELECT merchant_id, event_type, payload::text AS payload, published_at, attempts
                        FROM outbox_events WHERE aggregate_id = ?""")
                .param(aggregateId)
                .query((rs, n) -> new Row(rs.getLong("merchant_id"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getObject("published_at"), rs.getInt("attempts")))
                .list();
    }
}

package com.ledgerline.gateway.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** One refund on a payment. The money movement itself is a REFUND journal entry in the ledger. */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    private UUID paymentId;

    private long merchantId;

    private long amount;

    @Column(updatable = false)
    private Instant createdAt;

    protected Refund() {
        // for JPA
    }

    public static Refund of(Payment payment, long amount) {
        Refund refund = new Refund();
        refund.id = UUID.randomUUID();
        refund.paymentId = payment.getId();
        refund.merchantId = payment.getMerchantId();
        refund.amount = amount;
        refund.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return refund;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

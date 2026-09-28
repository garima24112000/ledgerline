package com.ledgerline.gateway.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;

/**
 * A card payment. Status changes go through the methods below, which allow only the legal
 * transitions; a Postgres trigger enforces the same rules for any other writer. {@code @Version}
 * makes two concurrent transitions of the same payment conflict instead of overwriting each other.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    private long merchantId;

    private long amount;

    private String currency;

    private String cardToken;

    private String merchantOrderId;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private long refundedAmount;

    private String declineReason;

    private String bankReference;

    private String idempotencyKey;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private Long version; // null until first saved, which is how Spring Data tells a new entity with an assigned id

    protected Payment() {
        // for JPA
    }

    public static Payment pending(long merchantId, String idempotencyKey, long amount, String currency,
                                  String cardToken, String merchantOrderId) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.merchantId = merchantId;
        payment.idempotencyKey = idempotencyKey;
        payment.amount = amount;
        payment.currency = currency;
        payment.cardToken = cardToken;
        payment.merchantOrderId = merchantOrderId;
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    public void capture(String bankReference) {
        requireStatus(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), PaymentStatus.CAPTURED);
        this.status = PaymentStatus.CAPTURED;
        this.bankReference = bankReference;
        this.updatedAt = now();
    }

    public void fail(String declineReason, String bankReference) {
        requireStatus(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
        this.declineReason = declineReason;
        this.bankReference = bankReference;
        this.updatedAt = now();
    }

    /** Also allowed from UNKNOWN: a retry that times out again leaves the payment UNKNOWN. */
    public void markUnknown() {
        requireStatus(EnumSet.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), PaymentStatus.UNKNOWN);
        this.status = PaymentStatus.UNKNOWN;
        this.updatedAt = now();
    }

    public void addRefund(long refundAmount) {
        if (status != PaymentStatus.CAPTURED || refundAmount <= 0 || refundAmount > refundableAmount()) {
            throw new IllegalStateException("can't refund " + refundAmount + " on payment " + id);
        }
        this.refundedAmount += refundAmount;
        this.updatedAt = now();
    }

    public long refundableAmount() {
        return amount - refundedAmount;
    }

    /** CAPTURED or FAILED: the bank's answer is known and recorded. */
    public boolean isResolved() {
        return status == PaymentStatus.CAPTURED || status == PaymentStatus.FAILED;
    }

    private void requireStatus(EnumSet<PaymentStatus> allowed, PaymentStatus target) {
        if (!allowed.contains(status)) {
            throw new IllegalStateException("payment " + id + ": illegal status transition " + status + " -> " + target);
        }
    }

    /** Postgres stores microseconds; truncating here keeps the entity equal to what is read back. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCardToken() {
        return cardToken;
    }

    public String getMerchantOrderId() {
        return merchantOrderId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getRefundedAmount() {
        return refundedAmount;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public String getBankReference() {
        return bankReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

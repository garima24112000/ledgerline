package com.ledgerline.gateway.payment;

/**
 * PENDING → CAPTURED | FAILED | UNKNOWN, and UNKNOWN → CAPTURED | FAILED.
 * UNKNOWN means the bank didn't answer in time: it may or may not have charged the card.
 */
public enum PaymentStatus {
    PENDING,
    CAPTURED,
    FAILED,
    UNKNOWN
}

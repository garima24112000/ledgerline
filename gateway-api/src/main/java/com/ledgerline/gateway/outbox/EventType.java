package com.ledgerline.gateway.outbox;

/** The events merchants receive as webhooks. {@link #wireName} is what goes into the event and the DB. */
public enum EventType {
    PAYMENT_CAPTURED("payment.captured"),
    PAYMENT_FAILED("payment.failed"),
    REFUND_CREATED("refund.created");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}

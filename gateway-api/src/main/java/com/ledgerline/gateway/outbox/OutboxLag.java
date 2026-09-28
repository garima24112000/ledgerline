package com.ledgerline.gateway.outbox;

/**
 * @param unpublished      outbox rows not yet published to Kafka
 * @param oldestAgeSeconds age of the oldest of them; 0 when there are none
 */
public record OutboxLag(long unpublished, double oldestAgeSeconds) {

    static final OutboxLag NONE = new OutboxLag(0, 0);
}

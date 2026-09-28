package com.ledgerline.gateway.idempotency;

/**
 * A stored idempotency key, as far as deciding what to do with a repeated request is concerned.
 *
 * @param lockExpired computed by Postgres ({@code locked_until <= now()}), so every app instance
 *                    judges expiry by the same clock
 */
public record IdempotencyRecord(
        String requestHash,
        IdempotencyStatus status,
        Integer responseCode,
        String responseBody,
        boolean lockExpired) {
}

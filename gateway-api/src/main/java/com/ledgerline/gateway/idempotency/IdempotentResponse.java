package com.ledgerline.gateway.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** A response for an idempotent endpoint, flagged when it is a replay of a stored one. */
public record IdempotentResponse<T>(HttpStatus status, T body, boolean replayed) {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public static <T> IdempotentResponse<T> fresh(HttpStatus status, T body) {
        return new IdempotentResponse<>(status, body, false);
    }

    public ResponseEntity<T> toResponseEntity() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (replayed) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(body);
    }
}

package com.ledgerline.gateway.idempotency;

/** Outcome of claiming an idempotency key: go ahead, or replay a stored response. */
public sealed interface Claim {

    /** This request now holds the key's lock and should do the work. */
    record Acquired() implements Claim {
    }

    /** The same request already completed; return its stored response. */
    record Replay(int responseCode, String responseBody) implements Claim {
    }
}

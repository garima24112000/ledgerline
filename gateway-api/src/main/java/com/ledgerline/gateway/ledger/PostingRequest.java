package com.ledgerline.gateway.ledger;

public record PostingRequest(long accountId, Direction direction, long amount) {

    public PostingRequest {
        if (direction == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("posting amount must be positive, was " + amount);
        }
    }

    public static PostingRequest debit(long accountId, long amount) {
        return new PostingRequest(accountId, Direction.DEBIT, amount);
    }

    public static PostingRequest credit(long accountId, long amount) {
        return new PostingRequest(accountId, Direction.CREDIT, amount);
    }

    /** This posting's effect on the account balance (balance = credits - debits). */
    long balanceDelta() {
        return direction == Direction.CREDIT ? amount : -amount;
    }
}

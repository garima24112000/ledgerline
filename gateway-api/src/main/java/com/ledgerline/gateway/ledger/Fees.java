package com.ledgerline.gateway.ledger;

public final class Fees {

    /** 2%, in basis points (1 bp = 0.01%). */
    static final long CAPTURE_FEE_BASIS_POINTS = 200;

    private static final long BASIS_POINTS_PER_WHOLE = 10_000;

    private Fees() {
    }

    /**
     * Platform fee for capturing {@code amount} minor units: 2%, rounded down.
     *
     * <p>{@code amount * 200 / 10_000} would overflow for amounts above ~4.6e16, so the amount is
     * split into whole multiples of 10,000 and a remainder. With amount = q * 10,000 + r:
     * {@code amount * bps / 10,000 = q * bps + r * bps / 10,000}, and neither term can overflow.
     */
    public static long captureFee(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        long wholes = amount / BASIS_POINTS_PER_WHOLE;
        long remainder = amount % BASIS_POINTS_PER_WHOLE;
        return wholes * CAPTURE_FEE_BASIS_POINTS + remainder * CAPTURE_FEE_BASIS_POINTS / BASIS_POINTS_PER_WHOLE;
    }
}

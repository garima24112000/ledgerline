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

    /**
     * Part of the capture fee to give back when refunding {@code amount} on top of
     * {@code refundedBefore} already refunded: {@code fee(before + amount) - fee(before)}.
     *
     * <p>Rounding each refund's fee on its own would drift: two refunds of 49 each return 0 + 0,
     * although the fee on 98 was 1. Taking the difference of cumulative fees makes the refunds
     * telescope: however a payment is split into refunds, the fee returned in total is exactly the
     * capture fee. Each step is >= 0 because the fee never decreases as the amount grows.
     */
    public static long refundFee(long refundedBefore, long amount) {
        if (refundedBefore < 0) {
            throw new IllegalArgumentException("refundedBefore must not be negative, was " + refundedBefore);
        }
        long feeBefore = refundedBefore == 0 ? 0 : captureFee(refundedBefore);
        return captureFee(Math.addExact(refundedBefore, amount)) - feeBefore;
    }
}

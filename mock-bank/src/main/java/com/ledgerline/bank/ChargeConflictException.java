package com.ledgerline.bank;

/** A paymentId was reused with a different amount or card. */
public class ChargeConflictException extends RuntimeException {

    public ChargeConflictException(String message) {
        super(message);
    }
}

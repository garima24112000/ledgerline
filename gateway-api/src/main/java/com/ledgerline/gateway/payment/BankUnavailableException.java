package com.ledgerline.gateway.payment;

/**
 * The bank gave no usable answer: timeout, connection error, or an error status. The charge may or
 * may not have happened, so this must never be treated as a decline.
 */
public class BankUnavailableException extends RuntimeException {

    public BankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

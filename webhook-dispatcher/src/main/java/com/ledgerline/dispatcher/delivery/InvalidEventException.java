package com.ledgerline.dispatcher.delivery;

/** The event isn't valid JSON with an id and merchant. Retrying can't help, so it goes straight to the DLT. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

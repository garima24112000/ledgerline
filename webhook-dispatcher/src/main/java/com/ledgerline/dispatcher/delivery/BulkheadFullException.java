package com.ledgerline.dispatcher.delivery;

/** The merchant already has the maximum number of webhook calls in flight. Retried via the retry topics. */
public class BulkheadFullException extends RuntimeException {

    public BulkheadFullException(long merchantId) {
        super("merchant " + merchantId + " has too many webhook calls in flight");
    }
}

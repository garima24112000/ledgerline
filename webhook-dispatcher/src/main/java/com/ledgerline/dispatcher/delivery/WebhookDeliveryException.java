package com.ledgerline.dispatcher.delivery;

/** The merchant didn't take the webhook (non-2xx, timeout, connection error). Retried via the retry topics. */
public class WebhookDeliveryException extends RuntimeException {

    public WebhookDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}

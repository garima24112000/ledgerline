package com.ledgerline.dispatcher.delivery;

public class WebhookTimeoutException extends WebhookDeliveryException {

    public WebhookTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

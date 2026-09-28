package com.ledgerline.dispatcher.delivery;

/** gateway-api doesn't know the merchant. Retrying can't help, so the event goes straight to the DLT. */
public class UnknownMerchantException extends RuntimeException {

    public UnknownMerchantException(long merchantId) {
        super("merchant " + merchantId + " does not exist");
    }
}

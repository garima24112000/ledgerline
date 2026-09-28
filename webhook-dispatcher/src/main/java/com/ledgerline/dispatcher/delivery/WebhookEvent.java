package com.ledgerline.dispatcher.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The fields the dispatcher needs from an event. The body sent to the merchant is the original JSON, untouched. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(String id, String type, long merchantId) {
}

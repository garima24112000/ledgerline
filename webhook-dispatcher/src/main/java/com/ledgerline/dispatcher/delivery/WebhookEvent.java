package com.ledgerline.dispatcher.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** The fields the dispatcher needs from an event. The body sent to the merchant is the original JSON, untouched. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(String id, String type, long merchantId, JsonNode data) {

    /**
     * The payment the event is about, for log correlation: {@code data.paymentId} on a refund event,
     * {@code data.id} on a payment event. Null if the event has neither.
     */
    public String paymentId() {
        if (data == null) {
            return null;
        }
        JsonNode id = data.hasNonNull("paymentId") ? data.get("paymentId") : data.get("id");
        return id == null || id.isNull() ? null : id.asText();
    }
}

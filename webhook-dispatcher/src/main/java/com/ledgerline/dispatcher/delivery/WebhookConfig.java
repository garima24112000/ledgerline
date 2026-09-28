package com.ledgerline.dispatcher.delivery;

/** A merchant's webhook endpoint and signing secret, as served by gateway-api. */
public record WebhookConfig(long merchantId, String webhookUrl, String webhookSecret) {
}

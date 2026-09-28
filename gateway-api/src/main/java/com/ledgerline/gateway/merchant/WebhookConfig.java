package com.ledgerline.gateway.merchant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where and how to deliver a merchant's webhooks")
public record WebhookConfig(
        @Schema(example = "1") long merchantId,
        @Schema(example = "http://localhost:8083/webhooks") String webhookUrl,
        @Schema(description = "HMAC secret used to sign webhook bodies", example = "whsec_demo_chaipoint") String webhookSecret) {
}

package com.ledgerline.bank;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Schema(description = "A card charge. paymentId is the idempotency key: retrying it returns the first result")
public record ChargeRequest(
        @NotNull
        @Schema(example = "3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11")
        UUID paymentId,
        @NotNull @Positive
        @Schema(description = "Amount in minor units (paise)", example = "49900")
        Long amount,
        @NotBlank
        @Schema(description = "Card token. tok_approve, tok_decline and tok_timeout force an outcome", example = "tok_visa_4242")
        String cardToken) {
}

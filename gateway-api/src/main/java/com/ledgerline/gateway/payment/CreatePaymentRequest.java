package com.ledgerline.gateway.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "A card payment to charge and capture")
public record CreatePaymentRequest(
        @NotNull @Positive
        @Schema(description = "Amount in minor units (paise). 49900 = ₹499.00", example = "49900")
        Long amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}")
        @Schema(description = "ISO 4217 currency code. Only INR is supported", example = "INR")
        String currency,
        @NotBlank @Size(max = 128)
        @Schema(description = "Tokenized card. The mock bank also accepts tok_approve, tok_decline and tok_timeout",
                example = "tok_visa_4242")
        String cardToken,
        @NotBlank @Size(max = 64)
        @Schema(description = "The merchant's own order reference", example = "order-1042")
        String merchantOrderId) {
}

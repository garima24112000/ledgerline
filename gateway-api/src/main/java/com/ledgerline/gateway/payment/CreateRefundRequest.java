package com.ledgerline.gateway.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "A full or partial refund")
public record CreateRefundRequest(
        @NotNull @Positive
        @Schema(description = "Amount to refund in minor units; at most the payment's amount minus earlier refunds",
                example = "10000")
        Long amount) {
}

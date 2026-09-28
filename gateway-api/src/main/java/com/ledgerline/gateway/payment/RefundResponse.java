package com.ledgerline.gateway.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A refund")
public record RefundResponse(
        @Schema(example = "8d2b6f4e-1c3a-4e9b-b7a2-5f0e9c1d2a34")
        UUID id,
        @Schema(example = "3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11")
        UUID paymentId,
        @Schema(description = "Minor units (paise)", example = "10000")
        long amount,
        @Schema(description = "The payment's total refunded amount after this refund", example = "10000")
        long paymentRefundedAmount,
        Instant createdAt) {

    static RefundResponse from(Refund refund, Payment payment) {
        return new RefundResponse(refund.getId(), payment.getId(), refund.getAmount(),
                payment.getRefundedAmount(), refund.getCreatedAt());
    }
}

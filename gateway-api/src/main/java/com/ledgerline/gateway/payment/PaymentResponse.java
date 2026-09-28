package com.ledgerline.gateway.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A payment")
public record PaymentResponse(
        @Schema(example = "3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11")
        UUID id,
        @Schema(description = "UNKNOWN: the bank didn't answer in time; the reconciler will resolve it", example = "CAPTURED")
        PaymentStatus status,
        @Schema(description = "Minor units (paise)", example = "49900")
        long amount,
        @Schema(example = "INR")
        String currency,
        @Schema(description = "Total refunded so far, in minor units", example = "0")
        long refundedAmount,
        @Schema(example = "order-1042")
        String merchantOrderId,
        @Schema(description = "Set only when FAILED", example = "insufficient_funds")
        String declineReason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getStatus(), payment.getAmount(), payment.getCurrency(),
                payment.getRefundedAmount(), payment.getMerchantOrderId(), payment.getDeclineReason(),
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}

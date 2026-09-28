package com.ledgerline.bank;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "The bank's decision on a charge")
public record ChargeResponse(
        UUID paymentId,
        ChargeStatus status,
        @Schema(description = "Set only when DECLINED", example = "insufficient_funds")
        String declineReason,
        @Schema(description = "The bank's own reference for the charge", example = "bnk_7c9e6679")
        String bankReference) {
}

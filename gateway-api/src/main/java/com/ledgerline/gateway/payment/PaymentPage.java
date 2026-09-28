package com.ledgerline.gateway.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "One page of payments, newest first")
public record PaymentPage(
        List<PaymentResponse> data,
        @Schema(description = "Pass as `cursor` to get the next page; null on the last page",
                example = "MTc1OTAxNjQ4MjE1MTIzNDozZjFjMmE5ZS02ZjBhLTRhNTctOWE1MS0yZjRmNWY3YTBjMTE")
        String nextCursor) {
}

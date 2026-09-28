package com.ledgerline.gateway.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The one error shape every endpoint returns: {@code {"error": {"code": ..., "message": ...}}}. */
@Schema(name = "ApiError", description = "Error response")
public record ApiError(ErrorBody error) {

    public static ApiError of(String code, String message) {
        return new ApiError(new ErrorBody(code, message));
    }

    @Schema(name = "ApiErrorBody")
    public record ErrorBody(
            @Schema(description = "Stable, machine-readable error code", example = "IDEMPOTENCY_KEY_IN_USE")
            String code,
            @Schema(description = "Human-readable explanation", example = "A request with this Idempotency-Key is still in progress")
            String message) {
    }
}

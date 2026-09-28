package com.ledgerline.gateway.payment;

import com.ledgerline.gateway.api.ApiError;
import com.ledgerline.gateway.idempotency.IdempotentResponse;
import com.ledgerline.gateway.security.MerchantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments", description = "Charge cards, look up and list payments, refund them")
@SecurityRequirement(name = "ApiKey")
@ApiResponse(responseCode = "401", description = "Missing or invalid X-Api-Key",
        content = @Content(schema = @Schema(implementation = ApiError.class),
                examples = @ExampleObject(value = PaymentController.UNAUTHORIZED_EXAMPLE)))
public class PaymentController {

    static final String IDEMPOTENCY_KEY_DESCRIPTION = """
            Unique key for this request, chosen by the client (a UUID is a good choice). Retrying with the
            same key and body returns the original response with `Idempotent-Replayed: true` instead of
            doing the work again. Same key while the first request is still running: 409. Same key with a
            different body: 422.""";

    static final String UNAUTHORIZED_EXAMPLE = """
            {"error": {"code": "UNAUTHORIZED", "message": "Missing or invalid credentials"}}""";

    private static final String PAYMENT_EXAMPLE = """
            {"id": "3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11", "status": "CAPTURED", "amount": 49900, "currency": "INR",
             "refundedAmount": 0, "merchantOrderId": "order-1042", "declineReason": null,
             "createdAt": "2026-09-27T10:15:30.123456Z", "updatedAt": "2026-09-27T10:15:30.345678Z"}""";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(
            summary = "Create a payment",
            description = """
                    Charges the card through the bank and, if approved, captures the payment and records it in the \
                    ledger. If the bank doesn't answer within 2 seconds the payment is UNKNOWN (202): retry with the \
                    same Idempotency-Key, or wait for the reconciler to resolve it.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(value = """
                            {"amount": 49900, "currency": "INR", "cardToken": "tok_visa_4242", "merchantOrderId": "order-1042"}"""))))
    @ApiResponse(responseCode = "201", description = "Payment created: CAPTURED, or FAILED if the bank declined",
            headers = @Header(name = IdempotentResponse.REPLAYED_HEADER, description = "`true` when this is a replayed response",
                    schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = PaymentResponse.class),
                    examples = @ExampleObject(value = PAYMENT_EXAMPLE)))
    @ApiResponse(responseCode = "202", description = "The bank didn't answer in time; the payment is UNKNOWN",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid body or missing Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "MISSING_IDEMPOTENCY_KEY", "message": "The Idempotency-Key header is required"}}""")))
    @ApiResponse(responseCode = "409", description = "A request with this Idempotency-Key is still in progress",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "IDEMPOTENCY_KEY_IN_USE", "message": "A request with this Idempotency-Key is still in progress; retry later"}}""")))
    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different body, or unsupported currency",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "IDEMPOTENCY_KEY_REUSED", "message": "This Idempotency-Key was already used for a different request"}}""")))
    public ResponseEntity<PaymentResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal MerchantPrincipal merchant,
            @Parameter(in = ParameterIn.HEADER, required = true, description = IDEMPOTENCY_KEY_DESCRIPTION,
                    example = "7b0c7c9a-2d3e-4a8b-9f11-0c5d2e6a7b10")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        return paymentService.create(merchant.merchantId(), idempotencyKey, request).toResponseEntity();
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment", description = "Only the merchant that created a payment can see it.")
    @ApiResponse(responseCode = "200", description = "The payment",
            content = @Content(schema = @Schema(implementation = PaymentResponse.class),
                    examples = @ExampleObject(value = PAYMENT_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "No such payment for this merchant",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "NOT_FOUND", "message": "Payment 3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11 not found"}}""")))
    public PaymentResponse get(@Parameter(hidden = true) @AuthenticationPrincipal MerchantPrincipal merchant,
                               @PathVariable UUID paymentId) {
        return paymentService.get(merchant.merchantId(), paymentId);
    }

    @GetMapping
    @Operation(
            summary = "List payments",
            description = """
                    Newest first, with cursor pagination: pass the previous page's `nextCursor` as `cursor`. \
                    Pages stay consistent while new payments arrive, and a deep page is as fast as the first.""")
    @ApiResponse(responseCode = "200", description = "One page of payments",
            content = @Content(schema = @Schema(implementation = PaymentPage.class)))
    @ApiResponse(responseCode = "400", description = "Invalid filter, limit or cursor",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "INVALID_CURSOR", "message": "The cursor is not valid"}}""")))
    public PaymentPage list(
            @Parameter(hidden = true) @AuthenticationPrincipal MerchantPrincipal merchant,
            @Parameter(description = "Only payments with this status")
            @RequestParam(required = false) PaymentStatus status,
            @Parameter(description = "Created at or after (ISO-8601)", example = "2026-09-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Created before (ISO-8601, exclusive)", example = "2026-10-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "`nextCursor` from the previous page")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "Page size, 1 to 100")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return paymentService.list(merchant.merchantId(), status, from, to, cursor, limit);
    }

    @PostMapping("/{paymentId}/refunds")
    @Operation(
            summary = "Refund a payment",
            description = """
                    Full or partial. The total refunded can't exceed the captured amount. Recorded as a reversing \
                    ledger entry that also returns the matching part of the platform fee.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(value = """
                            {"amount": 10000}"""))))
    @ApiResponse(responseCode = "201", description = "Refund recorded",
            headers = @Header(name = IdempotentResponse.REPLAYED_HEADER, description = "`true` when this is a replayed response",
                    schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = RefundResponse.class), examples = @ExampleObject(value = """
                    {"id": "8d2b6f4e-1c3a-4e9b-b7a2-5f0e9c1d2a34", "paymentId": "3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11",
                     "amount": 10000, "paymentRefundedAmount": 10000, "createdAt": "2026-09-27T11:00:00.000001Z"}""")))
    @ApiResponse(responseCode = "400", description = "Invalid body or missing Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such payment for this merchant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Payment isn't CAPTURED, or the Idempotency-Key is in use",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "PAYMENT_NOT_REFUNDABLE", "message": "Only CAPTURED payments can be refunded; this one is FAILED"}}""")))
    @ApiResponse(responseCode = "422", description = "Refund exceeds the refundable amount, or Idempotency-Key reused",
            content = @Content(schema = @Schema(implementation = ApiError.class), examples = @ExampleObject(value = """
                    {"error": {"code": "REFUND_EXCEEDS_CAPTURED", "message": "Refund of 60000 exceeds the refundable amount of 49900"}}""")))
    public ResponseEntity<RefundResponse> refund(
            @Parameter(hidden = true) @AuthenticationPrincipal MerchantPrincipal merchant,
            @PathVariable UUID paymentId,
            @Parameter(in = ParameterIn.HEADER, required = true, description = IDEMPOTENCY_KEY_DESCRIPTION,
                    example = "c4a1e2f0-9b8d-4c7e-a6f5-3d2c1b0a9e8f")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {
        return paymentService.refund(merchant.merchantId(), paymentId, idempotencyKey, request).toResponseEntity();
    }
}

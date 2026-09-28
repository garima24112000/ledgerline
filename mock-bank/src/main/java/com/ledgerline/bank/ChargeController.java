package com.ledgerline.bank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Charges", description = "Fake card processor")
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    private final ChargeService chargeService;

    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    @PostMapping("/charge")
    @Operation(
            summary = "Charge a card",
            description = "Approves, declines, or sleeps past the caller's timeout, at the configured rates. "
                    + "Idempotent on paymentId: a retry returns the first decision immediately.")
    @ApiResponse(responseCode = "200", description = "The decision (APPROVED or DECLINED)")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "409", description = "paymentId reused with a different amount or card")
    public ChargeResponse charge(@Valid @RequestBody ChargeRequest request) {
        // paymentId (and the gateway's traceId, via traceparent) on every line, to follow one payment across apps.
        try (MDC.MDCCloseable ignored = MDC.putCloseable("paymentId", request.paymentId().toString())) {
            ChargeResponse response = chargeService.charge(request);
            log.info("Charge of {} answered {}", request.amount(), response.status());
            return response;
        }
    }

    @GetMapping("/charges/{paymentId}")
    @Operation(summary = "Look up a charge", description = "Used by the gateway to resolve charges whose response it never received.")
    @ApiResponse(responseCode = "200", description = "The stored decision")
    @ApiResponse(responseCode = "404", description = "The bank never received a charge for this paymentId")
    public ResponseEntity<ChargeResponse> find(@PathVariable UUID paymentId) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("paymentId", paymentId.toString())) {
            Optional<ChargeResponse> charge = chargeService.find(paymentId);
            log.info("Lookup answered {}", charge.map(c -> c.status().toString()).orElse("NOT_FOUND"));
            return ResponseEntity.of(charge);
        }
    }

    @ExceptionHandler(ChargeConflictException.class)
    ProblemDetail conflict(ChargeConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

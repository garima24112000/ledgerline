package com.ledgerline.bank;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
        return chargeService.charge(request);
    }

    @GetMapping("/charges/{paymentId}")
    @Operation(summary = "Look up a charge", description = "Used by the gateway to resolve charges whose response it never received.")
    @ApiResponse(responseCode = "200", description = "The stored decision")
    @ApiResponse(responseCode = "404", description = "The bank never received a charge for this paymentId")
    public ResponseEntity<ChargeResponse> find(@PathVariable UUID paymentId) {
        return ResponseEntity.of(chargeService.find(paymentId));
    }

    @ExceptionHandler(ChargeConflictException.class)
    ProblemDetail conflict(ChargeConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

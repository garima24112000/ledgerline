package com.ledgerline.gateway.ledger;

import com.ledgerline.gateway.api.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/ledger")
@Tag(name = "Ledger admin", description = "Operator endpoints for the double-entry ledger")
@SecurityRequirement(name = "AdminBasic")
public class LedgerAdminController {

    private final LedgerService ledgerService;

    public LedgerAdminController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/verify")
    @Operation(
            summary = "Verify ledger integrity",
            description = "Checks that every journal entry balances, that every cached account balance equals "
                    + "the sum of its postings, and that the global net of all balances is 0. "
                    + "Scans the whole ledger, so it is meant for operators and scheduled checks, not hot paths.")
    @ApiResponse(responseCode = "200", description = "Verification report; check the `consistent` field")
    @ApiResponse(responseCode = "401", description = "Missing or invalid admin credentials",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Authenticated, but not an admin (e.g. a merchant API key)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public LedgerVerification verify() {
        return ledgerService.verify();
    }
}

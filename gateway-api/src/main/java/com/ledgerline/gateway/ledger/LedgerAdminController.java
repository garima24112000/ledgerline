package com.ledgerline.gateway.ledger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/ledger")
@Tag(name = "Ledger admin", description = "Operator endpoints for the double-entry ledger")
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
    public LedgerVerification verify() {
        return ledgerService.verify();
    }
}

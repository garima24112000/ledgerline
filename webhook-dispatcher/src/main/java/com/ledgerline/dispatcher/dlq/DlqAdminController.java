package com.ledgerline.dispatcher.dlq;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.concurrent.ExecutionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dlq")
@Tag(name = "Admin", description = "Operator endpoints (HTTP Basic)")
@SecurityRequirement(name = "AdminBasic")
public class DlqAdminController {

    private final DeadLetterQueue deadLetterQueue;

    public DlqAdminController(DeadLetterQueue deadLetterQueue) {
        this.deadLetterQueue = deadLetterQueue;
    }

    @Schema(description = "Result of a DLQ replay")
    public record ReplayResponse(
            @Schema(description = "Dead-lettered events sent back to payment.events", example = "3") int replayed) {
    }

    @PostMapping("/replay")
    @Operation(summary = "Replay dead-lettered webhooks",
            description = "Sends every event currently in payment.events-dlt back to payment.events, where it gets a "
                    + "fresh set of delivery attempts. Event ids are unchanged, so merchants can dedupe.")
    @ApiResponse(responseCode = "200", description = "Replay done")
    @ApiResponse(responseCode = "401", description = "Missing or wrong admin credentials")
    public ReplayResponse replay() throws ExecutionException, InterruptedException {
        return new ReplayResponse(deadLetterQueue.replay());
    }
}

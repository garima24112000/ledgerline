package com.ledgerline.gateway.merchant;

import com.ledgerline.gateway.api.ApiError;
import com.ledgerline.gateway.api.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/merchants")
@Tag(name = "Internal", description = "Service-to-service endpoints (X-Service-Token)")
@SecurityRequirement(name = "ServiceToken")
public class InternalMerchantController {

    private final MerchantRepository merchantRepository;

    public InternalMerchantController(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @GetMapping("/{merchantId}/webhook-config")
    @Operation(summary = "Get a merchant's webhook URL and signing secret", description = "Used by webhook-dispatcher.")
    @ApiResponse(responseCode = "200", description = "Webhook configuration")
    @ApiResponse(responseCode = "401", description = "Missing or invalid service token",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such merchant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public WebhookConfig webhookConfig(@PathVariable long merchantId) {
        return merchantRepository.findById(merchantId)
                .map(m -> new WebhookConfig(m.getId(), m.getWebhookUrl(), m.getWebhookSecret()))
                .orElseThrow(() -> ApiException.notFound("Merchant " + merchantId + " not found"));
    }
}

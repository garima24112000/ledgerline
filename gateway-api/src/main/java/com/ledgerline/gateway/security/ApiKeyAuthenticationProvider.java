package com.ledgerline.gateway.security;

import com.ledgerline.gateway.merchant.MerchantRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

/** Hashes the presented key and looks the hash up in {@code merchants.api_key_hash} (unique index). */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

    private final MerchantRepository merchantRepository;

    public ApiKeyAuthenticationProvider(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String apiKey = (String) authentication.getCredentials();
        return merchantRepository.findByApiKeyHash(ApiKeys.hash(apiKey))
                .map(merchant -> ApiKeyAuthenticationToken.authenticated(
                        new MerchantPrincipal(merchant.getId(), merchant.getName())))
                .orElseThrow(() -> new BadCredentialsException("Invalid API key"));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

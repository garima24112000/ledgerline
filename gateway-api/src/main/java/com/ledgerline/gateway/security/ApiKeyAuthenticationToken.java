package com.ledgerline.gateway.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Before authentication it carries the raw API key; afterwards the {@link MerchantPrincipal}. */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String apiKey;
    private final MerchantPrincipal merchant;

    private ApiKeyAuthenticationToken(String apiKey, MerchantPrincipal merchant) {
        super(merchant == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        this.apiKey = apiKey;
        this.merchant = merchant;
        setAuthenticated(merchant != null);
    }

    public static ApiKeyAuthenticationToken unauthenticated(String apiKey) {
        return new ApiKeyAuthenticationToken(apiKey, null);
    }

    public static ApiKeyAuthenticationToken authenticated(MerchantPrincipal merchant) {
        return new ApiKeyAuthenticationToken(null, merchant);
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return merchant;
    }
}

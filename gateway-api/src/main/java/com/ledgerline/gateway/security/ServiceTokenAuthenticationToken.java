package com.ledgerline.gateway.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** An internal service (webhook-dispatcher) calling /internal/**. */
public class ServiceTokenAuthenticationToken extends AbstractAuthenticationToken {

    private final String token;

    private ServiceTokenAuthenticationToken(String token, boolean authenticated) {
        super(authenticated ? List.of(new SimpleGrantedAuthority("ROLE_SERVICE")) : List.of());
        this.token = token;
        setAuthenticated(authenticated);
    }

    public static ServiceTokenAuthenticationToken unauthenticated(String token) {
        return new ServiceTokenAuthenticationToken(token, false);
    }

    public static ServiceTokenAuthenticationToken authenticated() {
        return new ServiceTokenAuthenticationToken(null, true);
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getPrincipal() {
        return "internal-service";
    }
}

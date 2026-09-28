package com.ledgerline.gateway.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Internal service authentication from the {@code X-Service-Token} header. */
public class ServiceTokenAuthenticationFilter extends HeaderAuthenticationFilter {

    public static final String HEADER = "X-Service-Token";

    public ServiceTokenAuthenticationFilter(AuthenticationManager authenticationManager, AuthenticationEntryPoint entryPoint) {
        super(HEADER, authenticationManager, entryPoint);
    }

    @Override
    protected Authentication unauthenticatedToken(String headerValue) {
        return ServiceTokenAuthenticationToken.unauthenticated(headerValue);
    }
}

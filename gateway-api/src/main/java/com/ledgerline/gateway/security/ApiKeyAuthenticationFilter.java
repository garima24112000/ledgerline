package com.ledgerline.gateway.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Merchant authentication from the {@code X-Api-Key} header. */
public class ApiKeyAuthenticationFilter extends HeaderAuthenticationFilter {

    public static final String HEADER = "X-Api-Key";

    public ApiKeyAuthenticationFilter(AuthenticationManager authenticationManager, AuthenticationEntryPoint entryPoint) {
        super(HEADER, authenticationManager, entryPoint);
    }

    @Override
    protected Authentication unauthenticatedToken(String headerValue) {
        return ApiKeyAuthenticationToken.unauthenticated(headerValue);
    }
}

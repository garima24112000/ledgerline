package com.ledgerline.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from one header. No header: the request continues anonymously and the
 * authorization rules decide. A header that fails authentication: 401 straight away.
 */
public abstract class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private final String headerName;
    private final AuthenticationManager authenticationManager;
    private final AuthenticationEntryPoint entryPoint;

    protected HeaderAuthenticationFilter(String headerName, AuthenticationManager authenticationManager,
                                         AuthenticationEntryPoint entryPoint) {
        this.headerName = headerName;
        this.authenticationManager = authenticationManager;
        this.entryPoint = entryPoint;
    }

    /** Wraps the raw header value in the token type its provider supports. */
    protected abstract Authentication unauthenticatedToken(String headerValue);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Authentication result = authenticationManager.authenticate(unauthenticatedToken(value));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException e) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, e);
            return;
        }
        chain.doFilter(request, response);
    }
}

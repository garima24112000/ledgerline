package com.ledgerline.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

public class ServiceTokenAuthenticationProvider implements AuthenticationProvider {

    private final byte[] expectedToken;

    public ServiceTokenAuthenticationProvider(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        byte[] presented = ((String) authentication.getCredentials()).getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison: String.equals returns at the first differing byte, which leaks
        // through response timing how much of a guess was right.
        if (!MessageDigest.isEqual(presented, expectedToken)) {
            throw new BadCredentialsException("Invalid service token");
        }
        return ServiceTokenAuthenticationToken.authenticated();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ServiceTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

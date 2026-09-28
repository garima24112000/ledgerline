package com.ledgerline.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.api.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes 401 and 403 in the same {@link ApiError} format as the controllers. Security errors
 * happen in the filter chain, before any controller, so the {@code @RestControllerAdvice} never sees them.
 */
@Component
public class JsonSecurityErrorHandler {

    private final ObjectMapper objectMapper;

    public JsonSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, e) -> write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Missing or invalid credentials");
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, e) -> write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "These credentials can't access this resource");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
    }
}

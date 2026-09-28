package com.ledgerline.gateway.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.api.ApiError;
import com.ledgerline.gateway.security.MerchantPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-merchant rate limit. It runs after authentication and authorization, so only requests from a
 * known merchant, to a path it may call, use a token. Admin, service and anonymous requests pass
 * through untouched.
 *
 * <p>If Redis fails, the request is allowed (fail open) and {@code rate_limiter_errors_total} counts
 * it: a cache outage must not take the payments API down with it.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Counter errors;

    public RateLimitFilter(RedisRateLimiter limiter, RateLimitProperties properties, MeterRegistry meterRegistry,
                           ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.errors = Counter.builder("rate_limiter_errors")
                .description("Rate-limit checks that failed (Redis unavailable); those requests were allowed")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!properties.enabled() || authentication == null
                || !(authentication.getPrincipal() instanceof MerchantPrincipal merchant)) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision;
        try {
            decision = limiter.tryAcquire(merchant.merchantId(), properties.limitFor(merchant.tier()));
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("Rate limiter unavailable, allowing request from merchant {}: {}", merchant.merchantId(), e.toString());
            chain.doFilter(request, response);
            return;
        }

        if (decision.allowed()) {
            response.setHeader(REMAINING_HEADER, String.valueOf(decision.remaining()));
            chain.doFilter(request, response);
            return;
        }

        Counter.builder("rate_limited_requests")
                .description("Requests rejected with 429 by the per-merchant rate limit")
                .tag("merchant", String.valueOf(merchant.merchantId()))
                .tag("tier", merchant.tier().name())
                .register(meterRegistry)
                .increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(decision.retryAfterMillis())));
        response.setHeader(REMAINING_HEADER, "0");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of("RATE_LIMITED",
                "Too many requests for your plan (" + merchant.tier() + "); retry after the Retry-After header"));
    }

    /** Retry-After is whole seconds, so round up: telling a client "0" would invite an immediate retry. */
    static long retryAfterSeconds(long retryAfterMillis) {
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}

package com.ledgerline.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.merchant.RateLimitTier;
import com.ledgerline.gateway.ratelimit.RateLimitProperties.TierLimit;
import com.ledgerline.gateway.security.MerchantPrincipal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final TierLimit FREE_LIMIT = new TierLimit(20, 40);
    private static final RateLimitProperties PROPERTIES = new RateLimitProperties(true, Map.of(
            RateLimitTier.FREE, FREE_LIMIT, RateLimitTier.PRO, new TierLimit(200, 400)));

    @Mock
    private RedisRateLimiter limiter;

    @Mock
    private FilterChain chain;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/payments");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(limiter, PROPERTIES, meters, new ObjectMapper());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowedRequestContinuesWithRemainingHeader() throws Exception {
        authenticateMerchant(7, RateLimitTier.FREE);
        when(limiter.tryAcquire(7, FREE_LIMIT)).thenReturn(new RateLimitDecision(true, 39, 0));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("39");
    }

    @Test
    void rejectedRequestGets429WithHeadersBodyAndMetric() throws Exception {
        authenticateMerchant(7, RateLimitTier.FREE);
        when(limiter.tryAcquire(7, FREE_LIMIT)).thenReturn(new RateLimitDecision(false, 0, 40));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1"); // 40 ms rounds up to 1 s, never 0
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(meters.get("rate_limited_requests").tags("merchant", "7", "tier", "FREE").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void redisFailureFailsOpenAndCountsTheError() throws Exception {
        authenticateMerchant(7, RateLimitTier.FREE);
        when(limiter.tryAcquire(anyLong(), any())).thenThrow(new RedisConnectionFailureException("down"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNull(); // unknown, so not sent
        assertThat(meters.get("rate_limiter_errors").counter().count()).isEqualTo(1.0);
    }

    @Test
    void nonMerchantRequestsAreNotLimited() throws Exception {
        // anonymous
        filter.doFilter(request, response, chain);
        // admin / service
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(limiter);
    }

    @Test
    void disabledLimiterIsANoOp() throws Exception {
        filter = new RateLimitFilter(limiter, new RateLimitProperties(false, PROPERTIES.tiers()), meters,
                new ObjectMapper());
        authenticateMerchant(7, RateLimitTier.FREE);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(limiter);
    }

    @Test
    void retryAfterRoundsUpToWholeSeconds() {
        assertThat(List.of(0L, 1L, 1000L, 1001L, 2500L))
                .map(RateLimitFilter::retryAfterSeconds)
                .containsExactly(1L, 1L, 1L, 2L, 3L);
    }

    private static void authenticateMerchant(long id, RateLimitTier tier) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new MerchantPrincipal(id, "Merchant " + id, tier), null, AuthorityUtils.createAuthorityList("ROLE_MERCHANT")));
    }
}

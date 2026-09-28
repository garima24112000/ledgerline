package com.ledgerline.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerline.gateway.AbstractGatewayIT;
import com.ledgerline.gateway.merchant.RateLimitTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** FREE is 1 request/s with a burst of 5 in tests (see {@link AbstractGatewayIT}). */
class RateLimitIT extends AbstractGatewayIT {

    private static final String PATH = "/v1/payments";

    @Test
    void burstIsAllowedThenExcessIsRejected() {
        TestMerchant merchant = createMerchant(RateLimitTier.FREE);

        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < TEST_FREE_BURST; i++) {
            ResponseEntity<String> response = getAs(merchant, PATH);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            remaining.add(response.getHeaders().getFirst("X-RateLimit-Remaining"));
        }
        assertThat(remaining).containsExactly("4", "3", "2", "1", "0");

        ResponseEntity<String> rejected = getAs(merchant, PATH);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getBody()).contains("\"code\":\"RATE_LIMITED\"");

        assertThat(rest.getForObject("/actuator/prometheus", String.class))
                .contains("rate_limited_requests_total{application=\"gateway-api\",merchant=\"%d\",tier=\"FREE\"} 1.0"
                        .formatted(merchant.id()));
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        TestMerchant merchant = createMerchant(RateLimitTier.FREE);
        exhaust(merchant);

        Thread.sleep(1_100); // one token at 1/s

        assertThat(getAs(merchant, PATH).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getAs(merchant, PATH).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void oneMerchantBeingLimitedDoesNotAffectAnother() {
        TestMerchant noisy = createMerchant(RateLimitTier.FREE);
        TestMerchant quiet = createMerchant(RateLimitTier.FREE);
        exhaust(noisy);

        assertThat(getAs(quiet, PATH).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void proMerchantsGetTheirOwnLargerLimit() {
        TestMerchant merchant = createMerchant(RateLimitTier.PRO);

        ResponseEntity<String> response = getAs(merchant, PATH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("399");
    }

    @Test
    void twoAppInstancesShareOneLimit() {
        TestMerchant merchant = createMerchant(RateLimitTier.FREE);
        try (ConfigurableApplicationContext other = startAnotherInstance(Map.of())) {
            String otherBase = baseUrl(other);

            // Alternate between the instances: with a limiter per instance, each would allow 5.
            int allowed = 0;
            List<HttpStatus> otherStatuses = new ArrayList<>();
            for (int i = 0; i < 2 * TEST_FREE_BURST; i++) {
                boolean useOther = i % 2 == 1;
                ResponseEntity<String> response = getAs(merchant, useOther ? otherBase + PATH : PATH);
                if (response.getStatusCode() == HttpStatus.OK) {
                    allowed++;
                }
                if (useOther) {
                    otherStatuses.add((HttpStatus) response.getStatusCode());
                }
            }

            assertThat(allowed).isEqualTo(TEST_FREE_BURST);
            assertThat(otherStatuses).contains(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Test
    void failedAuthenticationDoesNotUseTokens() {
        TestMerchant merchant = createMerchant(RateLimitTier.FREE);
        HttpHeaders wrongKey = new HttpHeaders();
        wrongKey.set("X-Api-Key", "sk_test_wrong");
        for (int i = 0; i < TEST_FREE_BURST + 1; i++) {
            assertThat(rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(wrongKey), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(getAs(merchant, PATH).getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("4");
    }

    /** Uses up the whole burst, then checks the next request is rejected. */
    private void exhaust(TestMerchant merchant) {
        for (int i = 0; i < TEST_FREE_BURST; i++) {
            assertThat(getAs(merchant, PATH).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(getAs(merchant, PATH).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}

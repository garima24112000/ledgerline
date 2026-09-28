package com.ledgerline.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerline.gateway.AbstractGatewayIT;
import com.ledgerline.gateway.merchant.RateLimitTier;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** A gateway instance whose Redis is unreachable keeps serving merchants and counts the failures. */
class RateLimitFailOpenIT extends AbstractGatewayIT {

    @Test
    void redisDownFailsOpen() throws IOException {
        TestMerchant merchant = createMerchant(RateLimitTier.FREE);
        try (ConfigurableApplicationContext instance = startAnotherInstance(Map.of("spring.data.redis.port", closedPort()))) {
            String base = baseUrl(instance);

            // Twice the burst: with a working limiter the second half would be 429.
            for (int i = 0; i < 2 * TEST_FREE_BURST; i++) {
                ResponseEntity<String> response = getAs(merchant, base + "/v1/payments");
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getHeaders().containsKey("X-RateLimit-Remaining")).isFalse();
            }

            MeterRegistry meters = instance.getBean(MeterRegistry.class);
            assertThat(meters.get("rate_limiter_errors").counter().count()).isEqualTo(2.0 * TEST_FREE_BURST);
            // Redis is not part of health, so the pod stays ready while the limiter is down.
            assertThat(rest.getForEntity(base + "/actuator/health", String.class).getBody()).contains("\"status\":\"UP\"");
        }
    }

    /** A port nothing listens on: connections are refused at once. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

package com.ledgerline.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The load-test profile: every charge rolls into the timeout bucket, but timeouts are disabled,
 * so it must be answered at normal latency, far below the 5 s timeout sleep.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bank.approve-rate=0",
        "bank.decline-rate=0",
        "bank.timeout-rate=1",
        "bank.latency-min=0ms",
        "bank.latency-max=5ms",
        "bank.timeout-sleep=5s",
        "bank.timeouts-enabled=false"})
class TimeoutsDisabledIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BankProperties properties;

    @Test
    void timeoutBucketIsAnsweredOnTime() {
        assertThat(properties.timeoutsEnabled()).isFalse();

        long started = System.nanoTime();
        ChargeResponse response = rest.postForObject("/charge",
                new ChargeRequest(UUID.randomUUID(), 100L, "tok_visa"), ChargeResponse.class);

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
        assertThat(response.status()).isEqualTo(ChargeStatus.APPROVED); // approve and decline rates are 0
    }
}

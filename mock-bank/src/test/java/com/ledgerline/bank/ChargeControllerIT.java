package com.ledgerline.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bank.latency-min=0ms",
        "bank.latency-max=5ms",
        "bank.timeout-sleep=300ms"})
class ChargeControllerIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BankProperties properties;

    @Test
    void timeoutsAreEnabledByDefault() {
        assertThat(properties.timeoutsEnabled()).isTrue();
    }

    @Test
    void retriedChargeReturnsTheSameResult() {
        ChargeRequest request = new ChargeRequest(UUID.randomUUID(), 49_900L, "tok_visa");

        ChargeResponse first = rest.postForObject("/charge", request, ChargeResponse.class);
        ChargeResponse retry = rest.postForObject("/charge", request, ChargeResponse.class);

        assertThat(first.paymentId()).isEqualTo(request.paymentId());
        assertThat(retry).isEqualTo(first);
    }

    @Test
    void timedOutChargeIsStoredAndCanBeLookedUp() {
        UUID paymentId = UUID.randomUUID();

        long started = System.nanoTime();
        rest.postForObject("/charge", new ChargeRequest(paymentId, 100L, "tok_timeout"), ChargeResponse.class);
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(300);

        ResponseEntity<ChargeResponse> found = rest.getForEntity("/charges/" + paymentId, ChargeResponse.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody().status()).isEqualTo(ChargeStatus.APPROVED);
    }

    @Test
    void declineTokenDeclines() {
        ChargeResponse response = rest.postForObject(
                "/charge", new ChargeRequest(UUID.randomUUID(), 100L, "tok_decline"), ChargeResponse.class);

        assertThat(response.status()).isEqualTo(ChargeStatus.DECLINED);
        assertThat(response.declineReason()).isEqualTo("insufficient_funds");
    }

    @Test
    void unknownChargeIs404() {
        assertThat(rest.getForEntity("/charges/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reusedPaymentIdWithDifferentAmountIs409() {
        UUID paymentId = UUID.randomUUID();
        rest.postForObject("/charge", new ChargeRequest(paymentId, 100L, "tok_approve"), ChargeResponse.class);

        ResponseEntity<String> conflict = rest.postForEntity("/charge", new ChargeRequest(paymentId, 200L, "tok_approve"), String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidRequestIs400() {
        ResponseEntity<String> response = rest.postForEntity("/charge", new ChargeRequest(UUID.randomUUID(), -5L, ""), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

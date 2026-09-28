package com.ledgerline.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChargeServiceTest {

    private static final BankProperties PROPERTIES = new BankProperties(
            0.85, 0.10, 0.05, Duration.ofMillis(50), Duration.ofMillis(300), Duration.ofSeconds(5), true);

    @Mock
    private RandomGenerator random;

    private final List<Duration> sleeps = new ArrayList<>();
    private ChargeService chargeService;

    @BeforeEach
    void setUp() {
        chargeService = new ChargeService(PROPERTIES, random, sleeps::add);
    }

    @Test
    void rollBelowApproveRateApprovesAfterNormalLatency() {
        when(random.nextDouble()).thenReturn(0.84);
        when(random.nextLong(251)).thenReturn(100L);

        ChargeResponse response = chargeService.charge(request("tok_visa"));

        assertThat(response.status()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(response.declineReason()).isNull();
        assertThat(response.bankReference()).startsWith("bnk_");
        assertThat(sleeps).containsExactly(Duration.ofMillis(150));
    }

    @Test
    void rollInDeclineBucketDeclines() {
        when(random.nextDouble()).thenReturn(0.90);

        ChargeResponse response = chargeService.charge(request("tok_visa"));

        assertThat(response.status()).isEqualTo(ChargeStatus.DECLINED);
        assertThat(response.declineReason()).isEqualTo("insufficient_funds");
    }

    @Test
    void rollInTimeoutBucketStoresTheDecisionThenSleepsPastTheCallerTimeout() {
        // 0.97 lands in the timeout bucket; 0.5 * 0.95 < 0.85 means the hidden outcome is APPROVED.
        when(random.nextDouble()).thenReturn(0.97, 0.5);
        ChargeRequest request = request("tok_visa");

        chargeService.charge(request);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(5));
        assertThat(chargeService.find(request.paymentId()))
                .hasValueSatisfying(stored -> assertThat(stored.status()).isEqualTo(ChargeStatus.APPROVED));
    }

    @Test
    void withTimeoutsDisabledTheTimeoutBucketIsAnsweredOnTime() {
        BankProperties noTimeouts = new BankProperties(
                0.85, 0.10, 0.05, Duration.ofMillis(50), Duration.ofMillis(300), Duration.ofSeconds(5), false);
        chargeService = new ChargeService(noTimeouts, random, sleeps::add);
        // 0.97 lands in the timeout bucket; 0.99 * 0.95 >= 0.85 means its outcome is DECLINED.
        when(random.nextDouble()).thenReturn(0.97, 0.99);
        when(random.nextLong(251)).thenReturn(100L);

        ChargeResponse response = chargeService.charge(request("tok_visa"));

        assertThat(response.status()).isEqualTo(ChargeStatus.DECLINED);
        assertThat(sleeps).containsExactly(Duration.ofMillis(150));
    }

    @Test
    void retryReturnsTheFirstDecisionWithoutSleepingAgain() {
        ChargeRequest request = request("tok_decline");
        ChargeResponse first = chargeService.charge(request);

        ChargeResponse retry = chargeService.charge(request);

        assertThat(retry).isEqualTo(first);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void reusingPaymentIdWithDifferentAmountIsAConflict() {
        ChargeRequest request = request("tok_approve");
        chargeService.charge(request);

        assertThatThrownBy(() -> chargeService.charge(new ChargeRequest(request.paymentId(), 1L, "tok_approve")))
                .isInstanceOf(ChargeConflictException.class);
    }

    @Test
    void magicTokensOverrideTheDice() {
        assertThat(chargeService.charge(request("tok_approve")).status()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(chargeService.charge(request("tok_decline")).status()).isEqualTo(ChargeStatus.DECLINED);
        assertThat(chargeService.charge(request("tok_timeout")).status()).isEqualTo(ChargeStatus.APPROVED);
        assertThat(sleeps).last().isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void unknownPaymentIsNotFound() {
        assertThat(chargeService.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void ratesThatDontSumToOneAreRejected() {
        assertThatThrownBy(() -> new BankProperties(0.9, 0.1, 0.05, Duration.ZERO, Duration.ZERO, Duration.ZERO, true))
                .hasMessageContaining("must be 1");
    }

    private static ChargeRequest request(String cardToken) {
        return new ChargeRequest(UUID.randomUUID(), 49_900L, cardToken);
    }
}

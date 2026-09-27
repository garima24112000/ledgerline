package com.ledgerline.gateway.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeesTest {

    @ParameterizedTest(name = "fee on {0} is {1}")
    @CsvSource({
            "1, 0",          // smallest amount: 0.02 rounds down to 0
            "49, 0",         // 0.98 still rounds down to 0
            "50, 1",         // first amount with a non-zero fee
            "99, 1",         // 1.98 rounds down, never up
            "100, 2",
            "10000, 200",    // exactly one "whole" of 10,000
            "10049, 200",    // 200.98 rounds down
            "123456789, 2469135",
    })
    void feeIsTwoPercentRoundedDown(long amount, long expectedFee) {
        assertThat(Fees.captureFee(amount)).isEqualTo(expectedFee);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE / 200 + 1, 4_611_686_018_427_387_904L})
    void veryLargeAmountsDoNotOverflow(long amount) {
        // Naive amount * 200 would overflow for all of these; compare with exact big-integer math.
        long expected = BigInteger.valueOf(amount)
                .multiply(BigInteger.valueOf(200))
                .divide(BigInteger.valueOf(10_000))
                .longValueExact();

        assertThat(Fees.captureFee(amount)).isEqualTo(expected).isPositive();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveAmounts(long amount) {
        assertThatThrownBy(() -> Fees.captureFee(amount)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void feeNeverExceedsTwoPercent() {
        for (long amount = 1; amount <= 20_000; amount++) {
            long fee = Fees.captureFee(amount);
            assertThat(fee * 50).isLessThanOrEqualTo(amount);       // fee <= 2%
            assertThat((fee + 1) * 50).isGreaterThan(amount);       // and it is the largest such value
        }
    }
}

package com.ledgerline.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.gateway.api.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentCursorTest {

    @Test
    void roundTripsWithMicrosecondPrecision() {
        PaymentCursor cursor = new PaymentCursor(Instant.parse("2026-09-27T10:15:30.123456Z"), UUID.randomUUID());

        String encoded = cursor.encode();

        assertThat(encoded).doesNotContain("=", "+", "/"); // URL-safe, no padding
        assertThat(PaymentCursor.decode(encoded)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-base64!", "MTIz", "YWJjOmRlZg"}) // "", garbage, "123", "abc:def"
    void garbageIsAClientError(String cursor) {
        assertThatThrownBy(() -> PaymentCursor.decode(cursor))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_CURSOR"));
    }
}

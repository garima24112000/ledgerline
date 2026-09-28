package com.ledgerline.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final String BODY = "{\"id\":\"evt_1\",\"merchantId\":1}";
    private static final Instant SIGNED_AT = Instant.ofEpochSecond(1_700_000_000);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    /** {@code printf '%s' '1700000000.{"id":"evt_1","merchantId":1}' | openssl dgst -sha256 -hmac whsec_test -hex} */
    private static final String HEADER = "t=1700000000,v1=51ded0cc60bb2c0246746f1775226fe6fb1d09ae98d3be2e7359f68b5464b0ee";

    @Test
    void acceptsTheDocumentedSignature() {
        assertThat(SignatureVerifier.isValid(HEADER, BODY, "whsec_test", SIGNED_AT.plusSeconds(30), TOLERANCE)).isTrue();
    }

    @Test
    void rejectsAWrongSecretOrATamperedBody() {
        assertThat(SignatureVerifier.isValid(HEADER, BODY, "whsec_other", SIGNED_AT, TOLERANCE)).isFalse();
        assertThat(SignatureVerifier.isValid(HEADER, BODY.replace("evt_1", "evt_2"), "whsec_test", SIGNED_AT, TOLERANCE)).isFalse();
    }

    @Test
    void rejectsAChangedTimestamp() {
        String moved = HEADER.replace("t=1700000000", "t=1700000001");

        assertThat(SignatureVerifier.isValid(moved, BODY, "whsec_test", SIGNED_AT, TOLERANCE)).isFalse();
    }

    @Test
    void rejectsSignaturesOutsideTheTolerance() {
        assertThat(SignatureVerifier.isValid(HEADER, BODY, "whsec_test", SIGNED_AT.plus(Duration.ofMinutes(6)), TOLERANCE)).isFalse();
        assertThat(SignatureVerifier.isValid(HEADER, BODY, "whsec_test", SIGNED_AT.minus(Duration.ofMinutes(6)), TOLERANCE)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertThat(SignatureVerifier.isValid(null, BODY, "whsec_test", SIGNED_AT, TOLERANCE)).isFalse();
        assertThat(SignatureVerifier.isValid("v1=abc", BODY, "whsec_test", SIGNED_AT, TOLERANCE)).isFalse();
        assertThat(SignatureVerifier.isValid("t=1700000000,v1=XYZ", BODY, "whsec_test", SIGNED_AT, TOLERANCE)).isFalse();
    }
}

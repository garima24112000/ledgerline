package com.ledgerline.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    private static final String BODY = "{\"id\":\"evt_1\",\"merchantId\":1}";
    private final WebhookSigner signer =
            new WebhookSigner(Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC));

    /**
     * Expected value computed independently:
     * {@code printf '%s' '1700000000.{"id":"evt_1","merchantId":1}' | openssl dgst -sha256 -hmac whsec_test -hex}
     */
    @Test
    void signsTimestampDotBodyWithHmacSha256() {
        assertThat(signer.sign("whsec_test", BODY))
                .isEqualTo("t=1700000000,v1=51ded0cc60bb2c0246746f1775226fe6fb1d09ae98d3be2e7359f68b5464b0ee");
    }

    @Test
    void anyChangeToTheBodyOrSecretChangesTheSignature() {
        String original = signer.sign("whsec_test", BODY);

        assertThat(signer.sign("whsec_test", BODY.replace("evt_1", "evt_2"))).isNotEqualTo(original);
        assertThat(signer.sign("whsec_other", BODY)).isNotEqualTo(original);
    }

    @Test
    void theTimestampIsPartOfTheSignedMessage() {
        WebhookSigner later = new WebhookSigner(Clock.fixed(Instant.ofEpochSecond(1_700_000_001), ZoneOffset.UTC));

        String v1Now = signer.sign("whsec_test", BODY).split(",v1=")[1];
        String v1Later = later.sign("whsec_test", BODY).split(",v1=")[1];
        assertThat(v1Later).isNotEqualTo(v1Now);
    }
}

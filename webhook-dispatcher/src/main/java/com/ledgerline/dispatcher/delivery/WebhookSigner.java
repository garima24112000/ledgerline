package com.ledgerline.dispatcher.delivery;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Builds {@code X-Ledgerline-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<body>")>}.
 * Signing the timestamp together with the body lets a merchant reject old captured requests replayed
 * later: changing {@code t} breaks the signature.
 */
@Component
public class WebhookSigner {

    private final Clock clock;

    public WebhookSigner(Clock clock) {
        this.clock = clock;
    }

    public String sign(String secret, String body) {
        long timestamp = clock.instant().getEpochSecond();
        return "t=" + timestamp + ",v1=" + hmacSha256Hex(secret, timestamp + "." + body);
    }

    static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e); // every JDK has it
        }
    }
}

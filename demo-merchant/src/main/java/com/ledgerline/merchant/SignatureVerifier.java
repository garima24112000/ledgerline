package com.ledgerline.merchant;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks {@code X-Ledgerline-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<body>")>}
 * the way any merchant would. Deliberately not shared with webhook-dispatcher: this is the
 * merchant's own code, written against the documented format.
 */
public final class SignatureVerifier {

    private SignatureVerifier() {
    }

    public static boolean isValid(String header, String body, String secret, Instant now, Duration tolerance) {
        if (header == null || !header.matches("t=\\d{1,12},v1=[0-9a-f]{64}")) {
            return false;
        }
        long timestamp = Long.parseLong(header.substring(2, header.indexOf(',')));
        if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(tolerance) > 0) {
            return false; // too old: possibly a captured request being replayed
        }
        byte[] expected = hmacSha256(secret, timestamp + "." + body);
        byte[] actual = HexFormat.of().parseHex(header.substring(header.indexOf("v1=") + 3));
        return MessageDigest.isEqual(expected, actual); // constant time: no timing hints about the right MAC
    }

    static byte[] hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}

package com.ledgerline.gateway.payment;

import com.ledgerline.gateway.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Position in the payment list: the (created_at, id) of the last row returned. The next page is
 * everything strictly "before" it in (created_at DESC, id DESC) order. The id breaks ties between
 * payments created in the same microsecond, so no row is skipped or repeated.
 *
 * <p>Encoded as opaque base64url of {@code epochMicros:uuid}. Clients must not build or parse it.
 */
public record PaymentCursor(Instant createdAt, UUID id) {

    public static PaymentCursor after(PaymentResponse lastRow) {
        return new PaymentCursor(lastRow.createdAt(), lastRow.id());
    }

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static PaymentCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, colon));
            UUID id = UUID.fromString(raw.substring(colon + 1));
            return new PaymentCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "The cursor is not valid");
        }
    }
}

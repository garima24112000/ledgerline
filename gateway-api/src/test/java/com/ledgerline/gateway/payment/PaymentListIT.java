package com.ledgerline.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.gateway.AbstractGatewayIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentListIT extends AbstractGatewayIT {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ObjectMapper objectMapper;

    private TestMerchant merchant;

    @BeforeEach
    void seed() {
        merchant = createMerchant();
        for (int i = 0; i < 25; i++) {
            // Every third pair shares a timestamp, so the id tie-breaker is exercised.
            Instant createdAt = T0.plusSeconds(i - (i % 3 == 1 ? 1 : 0));
            PaymentStatus status = i % 5 == 0 ? PaymentStatus.FAILED : PaymentStatus.CAPTURED;
            insertPayment(merchant.id(), 1_000 + i, status, createdAt);
        }
        TestMerchant other = createMerchant();
        insertPayment(other.id(), 1, PaymentStatus.CAPTURED, T0.plusSeconds(5)); // must never show up
    }

    @Test
    void walkingAllPagesReturnsEveryPaymentOnceInOrder() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = json(getAs(merchant, "/v1/payments?limit=7" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("data").forEach(p -> seen.add(p.get("id").asText()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(4); // 7 + 7 + 7 + 4
        assertThat(seen).containsExactlyElementsOf(expectedOrder("")); // Postgres' own uuid ordering
    }

    @Test
    void filtersByStatusAndTimeRange() throws Exception {
        JsonNode failed = json(getAs(merchant, "/v1/payments?status=FAILED&limit=100"));
        assertThat(failed.get("data")).hasSize(5);
        failed.get("data").forEach(p -> assertThat(p.get("status").asText()).isEqualTo("FAILED"));

        String from = T0.plusSeconds(10).toString();
        String to = T0.plusSeconds(20).toString();
        JsonNode window = json(getAs(merchant, "/v1/payments?limit=100&from=" + from + "&to=" + to));
        List<String> ids = new ArrayList<>();
        window.get("data").forEach(p -> ids.add(p.get("id").asText()));
        assertThat(ids).containsExactlyElementsOf(
                expectedOrder(" AND created_at >= '" + from + "' AND created_at < '" + to + "'"));
    }

    @Test
    void invalidCursorAndLimitAre400() throws Exception {
        ResponseEntity<String> badCursor = getAs(merchant, "/v1/payments?cursor=garbage");
        assertThat(badCursor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(badCursor).at("/error/code").asText()).isEqualTo("INVALID_CURSOR");

        assertThat(getAs(merchant, "/v1/payments?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAs(merchant, "/v1/payments?limit=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getAs(merchant, "/v1/payments?status=NOPE").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private List<String> expectedOrder(String extraCondition) {
        return jdbc.sql("SELECT id FROM payments WHERE merchant_id = ?" + extraCondition + " ORDER BY created_at DESC, id DESC")
                .param(merchant.id())
                .query(UUID.class)
                .list()
                .stream().map(UUID::toString).toList();
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful() || response.getStatusCode().is4xxClientError()).isTrue();
        return objectMapper.readTree(response.getBody());
    }
}

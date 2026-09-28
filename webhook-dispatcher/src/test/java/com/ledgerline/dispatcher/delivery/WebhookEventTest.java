package com.ledgerline.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class WebhookEventTest {

    private static final String PAYMENT_ID = "3f1c2a9e-6f0a-4a57-9a51-2f4f5f7a0c11";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void paymentEventNamesItsPaymentAsDataId() throws Exception {
        WebhookEvent event = objectMapper.readValue("""
                {"id": "evt-1", "type": "payment.captured", "merchantId": 1, "data": {"id": "%s"}}""".formatted(PAYMENT_ID),
                WebhookEvent.class);

        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void refundEventNamesItsPaymentAsDataPaymentId() throws Exception {
        WebhookEvent event = objectMapper.readValue("""
                {"id": "evt-2", "type": "refund.created", "merchantId": 1,
                 "data": {"id": "refund-9", "paymentId": "%s"}}""".formatted(PAYMENT_ID), WebhookEvent.class);

        assertThat(event.paymentId()).isEqualTo(PAYMENT_ID);
    }

    @Test
    void eventWithoutDataHasNoPaymentId() throws Exception {
        WebhookEvent event = objectMapper.readValue("""
                {"id": "evt-3", "type": "payment.captured", "merchantId": 1}""", WebhookEvent.class);

        assertThat(event.paymentId()).isNull();
    }

    @Test
    void logContextPutsTheIdsInTheMdcAndRemovesThemOnClose() {
        String json = """
                {"id": "evt-4", "type": "payment.failed", "merchantId": 2, "data": {"id": "%s"}}""".formatted(PAYMENT_ID);

        try (EventLogContext ignored = EventLogContext.of(objectMapper, json)) {
            assertThat(MDC.get("eventId")).isEqualTo("evt-4");
            assertThat(MDC.get("eventType")).isEqualTo("payment.failed");
            assertThat(MDC.get("merchantId")).isEqualTo("2");
            assertThat(MDC.get("paymentId")).isEqualTo(PAYMENT_ID);
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void logContextOfUnparseableEventIsEmpty() {
        try (EventLogContext ignored = EventLogContext.of(objectMapper, "not json")) {
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }
}

package com.ledgerline.dispatcher.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Delivers one event to its merchant. Throws when the merchant didn't take it; the Kafka retry
 * topics then take care of when to try again.
 */
@Service
public class WebhookDeliveryService {

    public static final String SIGNATURE_HEADER = "X-Ledgerline-Signature";
    public static final String EVENT_ID_HEADER = "X-Ledgerline-Event-Id";

    private final MerchantConfigClient merchantConfigs;
    private final MerchantBulkhead bulkhead;
    private final WebhookSigner signer;
    private final WebhookHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public WebhookDeliveryService(MerchantConfigClient merchantConfigs, MerchantBulkhead bulkhead, WebhookSigner signer,
                                  WebhookHttpClient httpClient, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.merchantConfigs = merchantConfigs;
        this.bulkhead = bulkhead;
        this.signer = signer;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param eventJson the event exactly as published; it is also the webhook body, byte for byte,
     *                  so the signature covers what the merchant receives
     * @throws BulkheadFullException    the merchant already has the maximum calls in flight
     * @throws WebhookDeliveryException non-2xx, timeout or connection error
     */
    public void deliver(String eventJson) {
        WebhookEvent event = parse(eventJson);
        WebhookConfig config = merchantConfigs.get(event.merchantId());

        if (!bulkhead.tryAcquire(event.merchantId())) {
            count("bulkhead_rejected");
            throw new BulkheadFullException(event.merchantId());
        }
        int status;
        try {
            status = httpClient.post(URI.create(config.webhookUrl()), eventJson, Map.of(
                    EVENT_ID_HEADER, event.id(),
                    SIGNATURE_HEADER, signer.sign(config.webhookSecret(), eventJson)));
        } catch (WebhookTimeoutException e) {
            count("timeout");
            throw e;
        } catch (WebhookDeliveryException e) {
            count("connection_error");
            throw e;
        } finally {
            bulkhead.release(event.merchantId());
        }

        if (status < 200 || status > 299) {
            count("http_error");
            throw new WebhookDeliveryException("merchant " + event.merchantId() + " answered " + status, null);
        }
        count("success");
    }

    private WebhookEvent parse(String eventJson) {
        try {
            WebhookEvent event = objectMapper.readValue(eventJson, WebhookEvent.class);
            if (event.id() == null || event.merchantId() <= 0) {
                throw new InvalidEventException("event without id or merchantId: " + eventJson, null);
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("event is not valid JSON", e);
        }
    }

    private void count(String result) {
        meterRegistry.counter("webhook.delivery", "result", result).increment();
    }
}

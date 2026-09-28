package com.ledgerline.dispatcher.delivery;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.ledgerline.dispatcher.config.DispatcherProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Looks up a merchant's webhook URL and secret on gateway-api, cached for {@code merchant-cache-ttl}.
 * The cache keeps gateway-api off the hot path (one call per merchant per TTL, not per event); the
 * TTL bounds how long a changed URL or rotated secret takes to be picked up.
 */
@Component
public class MerchantConfigClient {

    private final RestClient restClient;
    private final LoadingCache<Long, WebhookConfig> cache;

    public MerchantConfigClient(RestClient.Builder builder, DispatcherProperties properties) {
        this.restClient = builder
                .baseUrl(properties.gateway().baseUrl())
                .defaultHeader("X-Service-Token", properties.gateway().serviceToken())
                .build();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.merchantCacheTtl())
                .maximumSize(100_000)
                .build(this::fetch);
    }

    /**
     * @throws UnknownMerchantException if gateway-api has no such merchant (not retried)
     * @throws org.springframework.web.client.RestClientException if gateway-api can't be reached (retried)
     */
    public WebhookConfig get(long merchantId) {
        return cache.get(merchantId);
    }

    private WebhookConfig fetch(long merchantId) {
        try {
            return restClient.get()
                    .uri("/internal/merchants/{id}/webhook-config", merchantId)
                    .retrieve()
                    .body(WebhookConfig.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new UnknownMerchantException(merchantId);
        }
    }
}

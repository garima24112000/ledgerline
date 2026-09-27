package com.ledgerline.gateway.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String apiKeyHash;

    private String webhookUrl;

    private String webhookSecret;

    @Enumerated(EnumType.STRING)
    private RateLimitTier rateLimitTier;

    @Column(updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // for JPA
    }

    public Merchant(String name, String apiKeyHash, String webhookUrl, String webhookSecret, RateLimitTier rateLimitTier) {
        this.name = name;
        this.apiKeyHash = apiKeyHash;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.rateLimitTier = rateLimitTier;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public RateLimitTier getRateLimitTier() {
        return rateLimitTier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

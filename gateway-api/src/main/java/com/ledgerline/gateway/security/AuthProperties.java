package com.ledgerline.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator and service credentials, from environment variables in any real deployment.
 *
 * @param adminUsername HTTP Basic user for /admin/**
 * @param adminPassword HTTP Basic password for /admin/**
 * @param serviceToken  shared secret internal services send in X-Service-Token for /internal/**
 */
@ConfigurationProperties("gateway.auth")
public record AuthProperties(String adminUsername, String adminPassword, String serviceToken) {
}

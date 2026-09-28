package com.ledgerline.gateway.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param readTimeout how long to wait for the bank's answer before the payment becomes UNKNOWN
 */
@ConfigurationProperties("gateway.bank")
public record BankClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}

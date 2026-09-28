package com.ledgerline.merchant;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param failRate           share of valid webhooks answered with 500 (0.0 to 1.0), to simulate a flaky merchant
 * @param signatureTolerance how far a signature's timestamp may be from now
 * @param secrets            webhook secret per merchant id
 */
@ConfigurationProperties("demo-merchant")
public record DemoMerchantProperties(double failRate, Duration signatureTolerance, Map<Long, String> secrets) {
}

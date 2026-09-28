package com.ledgerline.dispatcher;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka container and one WireMock server for the whole run. WireMock plays both gateway-api
 * (the merchant config lookup) and the merchants' webhook endpoints. Retries are fast here:
 * 3 attempts, 200 ms then 400 ms.
 */
@AutoConfigureObservability // metrics export (/actuator/prometheus) is off in tests by default
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dispatcher.retry.max-attempts=3",
        "dispatcher.retry.initial-delay-ms=200",
        "dispatcher.retry.multiplier=2",
        "dispatcher.retry.max-delay-ms=1000",
        "dispatcher.http-timeout=2s"})
public abstract class AbstractDispatcherIT {

    protected static final String ADMIN_USER = "admin";
    protected static final String ADMIN_PASSWORD = "admin-dev-password";

    protected static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");
    protected static final WireMockServer wiremock = new WireMockServer(options().dynamicPort());

    static {
        kafka.start();
        wiremock.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("dispatcher.gateway.base-url", wiremock::baseUrl);
    }
}

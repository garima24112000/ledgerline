package com.ledgerline.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.ledgerline.gateway.ledger.Account;
import com.ledgerline.gateway.ledger.AccountRepository;
import com.ledgerline.gateway.merchant.Merchant;
import com.ledgerline.gateway.merchant.MerchantRepository;
import com.ledgerline.gateway.merchant.RateLimitTier;
import com.ledgerline.gateway.payment.PaymentStatus;
import com.ledgerline.gateway.security.ApiKeys;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Shared setup for gateway integration tests: one Postgres, one Kafka and one Redis container and one
 * WireMock "bank" for the whole run (started once, reused by every subclass), and one Spring context, because every
 * subclass has the same configuration.
 *
 * <p>Ledger rows can't be deleted, so tests never clean up. Each test creates its own merchant and
 * asserts only on that merchant's data, or on changes to shared rows. Merchants are PRO unless a test
 * asks for FREE, whose limit is lowered here (1 request/s, burst 5) so rate-limit tests are quick and
 * not timing-sensitive.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability // metrics export (/actuator/prometheus) is off in tests by default
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.reconciler.enabled=false", // tests call reconcileOnce() themselves
        "gateway.reconciler.min-age=0s",
        "gateway.outbox.relay-enabled=false", // tests call relayOnce() themselves
        "gateway.bank.read-timeout=1s",
        "gateway.rate-limit.tiers.FREE.rate-per-second=" + AbstractGatewayIT.TEST_FREE_RATE,
        "gateway.rate-limit.tiers.FREE.burst=" + AbstractGatewayIT.TEST_FREE_BURST})
public abstract class AbstractGatewayIT {

    protected static final int TEST_FREE_RATE = 1;
    protected static final int TEST_FREE_BURST = 5;

    protected static final String ADMIN_USER = "admin";
    protected static final String ADMIN_PASSWORD = "admin-dev-password";
    protected static final String SERVICE_TOKEN = "dev-internal-service-token";

    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");
    protected static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    protected static final WireMockServer bank = new WireMockServer(options().dynamicPort());

    static {
        postgres.start();
        kafka.start();
        redis.start();
        bank.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("gateway.bank.base-url", bank::baseUrl);
    }

    /**
     * Starts another gateway instance on the same containers, like a second replica. The caller
     * closes it. {@code overrides} replace any of the shared settings, e.g. to point at a dead Redis.
     */
    protected static ConfigurableApplicationContext startAnotherInstance(Map<String, Object> overrides) {
        Map<String, Object> properties = new HashMap<>(Map.of(
                "server.port", 0,
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", postgres.getUsername(),
                "spring.datasource.password", postgres.getPassword(),
                "spring.kafka.bootstrap-servers", kafka.getBootstrapServers(),
                "spring.data.redis.host", redis.getHost(),
                "spring.data.redis.port", redis.getMappedPort(6379),
                "gateway.bank.base-url", bank.baseUrl()));
        properties.putAll(Map.of(
                "gateway.reconciler.enabled", false,
                "gateway.outbox.relay-enabled", false,
                "gateway.rate-limit.tiers.FREE.rate-per-second", TEST_FREE_RATE,
                "gateway.rate-limit.tiers.FREE.burst", TEST_FREE_BURST));
        properties.putAll(overrides);
        // As command-line args: builder.properties(...) only sets defaults, which application.yml overrides.
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new SpringApplicationBuilder(GatewayApiApplication.class).run(args);
    }

    protected static String baseUrl(ConfigurableApplicationContext instance) {
        return "http://localhost:" + ((ServletWebServerApplicationContext) instance).getWebServer().getPort();
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void resetBank() {
        bank.resetAll();
    }

    protected record TestMerchant(long id, String apiKey, long payableAccountId) {
    }

    /** A fresh PRO merchant with a random API key and an INR payable account. */
    protected TestMerchant createMerchant() {
        return createMerchant(RateLimitTier.PRO);
    }

    protected TestMerchant createMerchant(RateLimitTier tier) {
        String apiKey = "sk_test_" + UUID.randomUUID();
        Merchant merchant = merchantRepository.save(new Merchant(
                "Merchant " + apiKey, ApiKeys.hash(apiKey), "http://merchant.test/hooks", "secret", tier));
        long payable = accountRepository.save(Account.merchantPayable(merchant.getId(), "INR")).getId();
        return new TestMerchant(merchant.getId(), apiKey, payable);
    }

    /** Inserts a payment row directly, for tests that need payments without going through the bank. */
    protected UUID insertPayment(long merchantId, long amount, PaymentStatus status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payments (id, merchant_id, amount, currency, card_token, merchant_order_id,
                                              status, idempotency_key, created_at, updated_at)
                        VALUES (:id, :merchantId, :amount, 'INR', 'tok_test', 'order', :status, :key, :createdAt, :createdAt)""")
                .param("id", id)
                .param("merchantId", merchantId)
                .param("amount", amount)
                .param("status", status.name())
                .param("key", "seed-" + id)
                .param("createdAt", createdAt.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    protected UUID insertPayment(long merchantId) {
        return insertPayment(merchantId, 10_000, PaymentStatus.PENDING, Instant.now());
    }

    // --- HTTP helpers ---

    protected ResponseEntity<String> postJson(String path, TestMerchant merchant, String idempotencyKey, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", merchant.apiKey());
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    protected ResponseEntity<String> createPayment(TestMerchant merchant, String idempotencyKey, long amount, String cardToken) {
        return postJson("/v1/payments", merchant, idempotencyKey, """
                {"amount": %d, "currency": "INR", "cardToken": "%s", "merchantOrderId": "order-1"}"""
                .formatted(amount, cardToken));
    }

    /** {@code path} may also be an absolute URL, to call another instance. */
    protected ResponseEntity<String> getAs(TestMerchant merchant, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", merchant.apiKey());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    // --- Bank stubs ---

    protected static void stubCharge(String status, int delayMillis) {
        bank.stubFor(post(urlEqualTo("/charge")).willReturn(bankAnswer(status).withFixedDelay(delayMillis)));
    }

    protected static void stubLookup(UUID paymentId, String status) {
        bank.stubFor(get(urlEqualTo("/charges/" + paymentId)).willReturn(bankAnswer(status)));
    }

    private static ResponseDefinitionBuilder bankAnswer(String status) {
        String reason = "DECLINED".equals(status) ? "\"insufficient_funds\"" : "null";
        return aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"paymentId": null, "status": "%s", "declineReason": %s, "bankReference": "bnk_test"}"""
                        .formatted(status, reason));
    }

    // --- Database helpers ---

    protected long balance(long accountId) {
        return jdbc.sql("SELECT balance FROM accounts WHERE id = ?").param(accountId).query(Long.class).single();
    }

    protected long journalEntries(UUID paymentId, String type) {
        return jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE payment_id = ? AND type = ?")
                .param(paymentId).param(type).query(Long.class).single();
    }
}

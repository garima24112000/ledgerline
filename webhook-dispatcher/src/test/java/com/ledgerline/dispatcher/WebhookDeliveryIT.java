package com.ledgerline.dispatcher;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.ledgerline.dispatcher.dlq.DeadLetterQueue;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * End to end through real Kafka: payment.events -> retry topics -> DLT -> replay. Each test uses its
 * own merchant id and webhook path, so tests don't see each other's requests.
 */
class WebhookDeliveryIT extends AbstractDispatcherIT {

    private static final String TOPIC = "payment.events";
    private static final String DLT = "payment.events-dlt";
    private static final AtomicLong merchantIds = new AtomicLong(1_000);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private DeadLetterQueue deadLetterQueue;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void retriesOn500AndLandsInTheDltAfterMaxAttempts() throws Exception {
        long merchant = merchantWithWebhook("whsec_" + UUID.randomUUID());
        stubMerchant(merchant, 500);
        double retriesBefore = meterRegistry.counter("webhook.retry").count();
        double deadLetteredBefore = meterRegistry.counter("webhook.dead.lettered").count();
        String eventId = publish(merchant);

        await().atMost(Duration.ofSeconds(30)).until(() -> dltContains(eventId));
        // The DLT handler consumes the letter after it lands on the topic, so wait for it too.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> meterRegistry.counter("webhook.dead.lettered").count() > deadLetteredBefore);

        List<LoggedRequest> calls = webhookCalls(merchant);
        assertThat(calls).hasSize(3); // max-attempts: the original + 2 retries
        for (LoggedRequest call : calls) {
            assertThat(call.getHeader("X-Ledgerline-Event-Id")).isEqualTo(eventId);
            assertValidSignature(call, secretOf(merchant));
        }
        assertThat(meterRegistry.counter("webhook.retry").count() - retriesBefore).isEqualTo(2);
        assertThat(meterRegistry.counter("webhook.delivery", "result", "http_error").count()).isGreaterThanOrEqualTo(3);

        deadLetterQueue.refreshSize();
        assertThat(deadLetterQueue.size()).isGreaterThanOrEqualTo(1);
        String metrics = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(metrics).contains("webhook_delivery_total{", "webhook_retry_total", "webhook_dead_lettered_total", "dlq_size");
    }

    @Test
    void recoversOnRetryWhenTheMerchantComesBack() throws Exception {
        long merchant = merchantWithWebhook("whsec_" + UUID.randomUUID());
        String path = webhookPath(merchant);
        wiremock.stubFor(post(urlEqualTo(path)).inScenario("flaky-" + merchant).whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
        wiremock.stubFor(post(urlEqualTo(path)).inScenario("flaky-" + merchant).whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
        String eventId = publish(merchant);

        await().atMost(Duration.ofSeconds(30)).until(() -> webhookCalls(merchant).size() == 2);
        Thread.sleep(1_500); // longer than every remaining backoff: nothing else may arrive

        assertThat(webhookCalls(merchant)).hasSize(2);
        assertThat(dltContains(eventId)).isFalse();
    }

    @Test
    void unknownMerchantGoesStraightToTheDltWithoutRetries() {
        long merchant = merchantIds.incrementAndGet();
        wiremock.stubFor(get(urlEqualTo("/internal/merchants/" + merchant + "/webhook-config"))
                .willReturn(aResponse().withStatus(404)));
        double retriesBefore = meterRegistry.counter("webhook.retry").count();
        double deadLetteredBefore = meterRegistry.counter("webhook.dead.lettered").count();
        String eventId = publish(merchant);

        await().atMost(Duration.ofSeconds(30)).until(() -> dltContains(eventId));
        // The DLT handler consumes the letter after it lands on the topic, so wait for it too.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> meterRegistry.counter("webhook.dead.lettered").count() > deadLetteredBefore);

        assertThat(meterRegistry.counter("webhook.retry").count()).isEqualTo(retriesBefore);
    }

    @Test
    void replayRedeliversDeadLetteredEvents() throws Exception {
        long merchant = merchantWithWebhook("whsec_" + UUID.randomUUID());
        stubMerchant(merchant, 500);
        String eventId = publish(merchant);
        await().atMost(Duration.ofSeconds(30)).until(() -> dltContains(eventId));

        stubMerchant(merchant, 200); // the merchant is fixed
        assertThat(rest.postForEntity("/admin/dlq/replay", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> replay = rest.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD)
                .postForEntity("/admin/dlq/replay", null, Map.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) replay.getBody().get("replayed")).isGreaterThanOrEqualTo(1);
        assertThat(deadLetterQueue.size()).isZero();
        await().atMost(Duration.ofSeconds(30)).until(() -> webhookCalls(merchant).size() == 4); // 3 failed + 1 ok
        assertThat(webhookCalls(merchant).getLast().getHeader("X-Ledgerline-Event-Id")).isEqualTo(eventId);
    }

    // --- helpers ---

    private long merchantWithWebhook(String secret) {
        long merchant = merchantIds.incrementAndGet();
        wiremock.stubFor(get(urlEqualTo("/internal/merchants/" + merchant + "/webhook-config"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"merchantId": %d, "webhookUrl": "%s", "webhookSecret": "%s"}"""
                        .formatted(merchant, wiremock.baseUrl() + webhookPath(merchant), secret))));
        return merchant;
    }

    private String secretOf(long merchant) {
        String body = wiremock.getStubMappings().stream()
                .filter(m -> m.getRequest().getUrl() != null
                        && m.getRequest().getUrl().equals("/internal/merchants/" + merchant + "/webhook-config"))
                .findFirst().orElseThrow().getResponse().getBody();
        return body.replaceAll("(?s).*\"webhookSecret\": \"([^\"]+)\".*", "$1");
    }

    private static void stubMerchant(long merchant, int status) {
        wiremock.stubFor(post(urlEqualTo(webhookPath(merchant))).willReturn(aResponse().withStatus(status)));
    }

    private static String webhookPath(long merchant) {
        return "/merchants/" + merchant + "/hooks";
    }

    private String publish(long merchant) {
        String eventId = UUID.randomUUID().toString();
        kafkaTemplate.send(TOPIC, String.valueOf(merchant), """
                {"id":"%s","type":"payment.captured","merchantId":%d,"createdAt":"2026-09-28T10:00:00Z","data":{"amount":100}}"""
                .formatted(eventId, merchant)).join();
        return eventId;
    }

    private static List<LoggedRequest> webhookCalls(long merchant) {
        return wiremock.findAll(postRequestedFor(urlEqualTo(webhookPath(merchant))));
    }

    /** Checks the signature the way a merchant would: HMAC-SHA256 over "<t>.<body>". */
    private static void assertValidSignature(LoggedRequest call, String secret) throws Exception {
        String header = call.getHeader("X-Ledgerline-Signature");
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        String timestamp = header.substring(2, header.indexOf(','));
        String v1 = header.substring(header.indexOf("v1=") + 3);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(
                (timestamp + "." + call.getBodyAsString()).getBytes(StandardCharsets.UTF_8)));
        assertThat(v1).isEqualTo(expected);
    }

    private static boolean dltContains(String eventId) {
        return readTopic(DLT).stream().anyMatch(r -> r.value().contains(eventId));
    }

    private static List<ConsumerRecord<String, String>> readTopic(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }
}

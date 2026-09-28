package com.ledgerline.dispatcher.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryServiceTest {

    private static final long MERCHANT_A = 1;
    private static final long MERCHANT_B = 2;
    private static final URI URL_A = URI.create("http://a.test/hooks");
    private static final URI URL_B = URI.create("http://b.test/hooks");

    @Mock
    private MerchantConfigClient merchantConfigs;
    @Mock
    private WebhookHttpClient httpClient;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final WebhookSigner signer = new WebhookSigner(Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC));
    private WebhookDeliveryService service;

    @BeforeEach
    void setUp() {
        lenient().when(merchantConfigs.get(MERCHANT_A)).thenReturn(new WebhookConfig(MERCHANT_A, URL_A.toString(), "secret-a"));
        lenient().when(merchantConfigs.get(MERCHANT_B)).thenReturn(new WebhookConfig(MERCHANT_B, URL_B.toString(), "secret-b"));
        service = new WebhookDeliveryService(merchantConfigs, new MerchantBulkhead(10), signer, httpClient,
                new ObjectMapper(), meterRegistry);
    }

    /**
     * Merchant A's endpoint hangs. Ten calls to it are held in flight (the mock blocks until
     * {@code releaseA}); the 11th for A is turned away without an HTTP call, while B is delivered.
     */
    @Test
    void bulkheadRejectsTheEleventhConcurrentDeliveryForOneMerchantButNotForAnother() throws Exception {
        CountDownLatch tenInFlight = new CountDownLatch(10);
        CountDownLatch releaseA = new CountDownLatch(1);
        when(httpClient.post(eq(URL_A), anyString(), anyMap())).thenAnswer(call -> {
            tenInFlight.countDown();
            releaseA.await();
            return 200;
        });
        when(httpClient.post(eq(URL_B), anyString(), anyMap())).thenReturn(200);

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<?>> slowCalls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String event = event("evt_a" + i, MERCHANT_A);
                slowCalls.add(pool.submit(() -> service.deliver(event)));
            }
            assertThat(tenInFlight.await(5, TimeUnit.SECONDS)).as("10 calls to A in flight").isTrue();

            assertThatThrownBy(() -> service.deliver(event("evt_a10", MERCHANT_A)))
                    .isInstanceOf(BulkheadFullException.class);
            service.deliver(event("evt_b", MERCHANT_B)); // B is unaffected by A's full bulkhead

            releaseA.countDown();
            for (Future<?> call : slowCalls) {
                call.get(5, TimeUnit.SECONDS); // all 10 finish successfully
            }
        }

        verify(httpClient, times(10)).post(eq(URL_A), anyString(), anyMap()); // the 11th never went out
        assertThat(count("success")).isEqualTo(11);
        assertThat(count("bulkhead_rejected")).isEqualTo(1);

        service.deliver(event("evt_a11", MERCHANT_A)); // permits were given back
        assertThat(count("success")).isEqualTo(12);
    }

    @Test
    void sendsTheEventUnchangedWithEventIdAndSignatureHeaders() {
        String event = event("evt_1", MERCHANT_A);
        when(httpClient.post(eq(URL_A), anyString(), anyMap())).thenReturn(204);

        service.deliver(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(httpClient).post(eq(URL_A), eq(event), headers.capture());
        assertThat(headers.getValue())
                .containsEntry(WebhookDeliveryService.EVENT_ID_HEADER, "evt_1")
                .containsEntry(WebhookDeliveryService.SIGNATURE_HEADER, signer.sign("secret-a", event));
    }

    @Test
    void non2xxIsAFailedDelivery() {
        when(httpClient.post(eq(URL_A), anyString(), anyMap())).thenReturn(500);

        assertThatThrownBy(() -> service.deliver(event("evt_1", MERCHANT_A)))
                .isInstanceOf(WebhookDeliveryException.class)
                .hasMessageContaining("answered 500");
        assertThat(count("http_error")).isEqualTo(1);
    }

    @Test
    void timeoutIsAFailedDeliveryAndFreesThePermit() {
        when(httpClient.post(eq(URL_A), anyString(), anyMap()))
                .thenThrow(new WebhookTimeoutException("no answer within PT5S", null))
                .thenReturn(200);

        assertThatThrownBy(() -> service.deliver(event("evt_1", MERCHANT_A))).isInstanceOf(WebhookTimeoutException.class);
        assertThat(count("timeout")).isEqualTo(1);

        service.deliver(event("evt_1", MERCHANT_A));
        assertThat(count("success")).isEqualTo(1);
    }

    @Test
    void malformedEventIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.deliver("not json")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> service.deliver("{\"type\":\"payment.captured\"}")).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(merchantConfigs, httpClient);
    }

    private static String event(String id, long merchantId) {
        return """
                {"id":"%s","type":"payment.captured","merchantId":%d,"data":{"amount":100}}""".formatted(id, merchantId);
    }

    private double count(String result) {
        return meterRegistry.counter("webhook.delivery", "result", result).count();
    }
}

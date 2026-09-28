package com.ledgerline.gateway.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for mock-bank. The calls block, which is fine because requests run on virtual threads:
 * a blocked virtual thread is parked and doesn't hold a platform thread.
 *
 * <p>Every call is timed as {@code bank_call_seconds{operation, outcome}}. The {@link RestClient.Builder}
 * is Boot's, so each call also carries the current trace as a W3C {@code traceparent} header.
 */
@Component
public class BankClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public BankClient(RestClient.Builder builder, BankClientProperties properties, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // The JDK client otherwise tries an HTTP/2 upgrade (h2c) on plain HTTP, which some
                // servers answer by resetting the stream. A plain internal call gains nothing from it.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    /**
     * Charges the card. The bank is idempotent on paymentId, so calling this again for the same
     * payment (a client retry after a timeout) can't charge twice.
     *
     * @throws BankUnavailableException if there is no definite answer
     */
    public BankResult charge(UUID paymentId, long amount, String cardToken) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "unavailable";
        try {
            ChargeResponse response = restClient.post()
                    .uri("/charge")
                    .body(new ChargeRequest(paymentId, amount, cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
            BankResult result = toResult(response);
            outcome = outcome(result);
            return result;
        } catch (RestClientException e) {
            throw new BankUnavailableException("no answer from the bank for payment " + paymentId, e);
        } finally {
            stop(sample, "charge", outcome);
        }
    }

    /**
     * Asks the bank what happened to a charge.
     *
     * @return empty if the bank never received it
     * @throws BankUnavailableException if there is no definite answer
     */
    public Optional<BankResult> lookup(UUID paymentId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "unavailable";
        try {
            ChargeResponse response = restClient.get()
                    .uri("/charges/{paymentId}", paymentId)
                    .retrieve()
                    .body(ChargeResponse.class);
            BankResult result = toResult(response);
            outcome = outcome(result);
            return Optional.of(result);
        } catch (HttpClientErrorException.NotFound e) {
            outcome = "not_found";
            return Optional.empty();
        } catch (RestClientException e) {
            throw new BankUnavailableException("could not look up payment " + paymentId + " at the bank", e);
        } finally {
            stop(sample, "lookup", outcome);
        }
    }

    private static String outcome(BankResult result) {
        return result.approved() ? "approved" : "declined";
    }

    private void stop(Timer.Sample sample, String operation, String outcome) {
        sample.stop(Timer.builder("bank.call")
                .description("Calls to the bank, including ones that timed out")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    private static BankResult toResult(ChargeResponse response) {
        return switch (response.status()) {
            case "APPROVED" -> BankResult.approved(response.bankReference());
            case "DECLINED" -> BankResult.declined(response.declineReason(), response.bankReference());
            default -> throw new BankUnavailableException("unexpected bank status " + response.status(), null);
        };
    }

    record ChargeRequest(UUID paymentId, long amount, String cardToken) {
    }

    record ChargeResponse(UUID paymentId, String status, String declineReason, String bankReference) {
    }
}

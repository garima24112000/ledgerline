package com.ledgerline.gateway.payment;

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
 */
@Component
public class BankClient {

    private final RestClient restClient;

    public BankClient(RestClient.Builder builder, BankClientProperties properties) {
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
        try {
            ChargeResponse response = restClient.post()
                    .uri("/charge")
                    .body(new ChargeRequest(paymentId, amount, cardToken))
                    .retrieve()
                    .body(ChargeResponse.class);
            return toResult(response);
        } catch (RestClientException e) {
            throw new BankUnavailableException("no answer from the bank for payment " + paymentId, e);
        }
    }

    /**
     * Asks the bank what happened to a charge.
     *
     * @return empty if the bank never received it
     * @throws BankUnavailableException if there is no definite answer
     */
    public Optional<BankResult> lookup(UUID paymentId) {
        try {
            ChargeResponse response = restClient.get()
                    .uri("/charges/{paymentId}", paymentId)
                    .retrieve()
                    .body(ChargeResponse.class);
            return Optional.of(toResult(response));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new BankUnavailableException("could not look up payment " + paymentId + " at the bank", e);
        }
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

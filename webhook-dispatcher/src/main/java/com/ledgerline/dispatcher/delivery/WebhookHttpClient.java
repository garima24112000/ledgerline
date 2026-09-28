package com.ledgerline.dispatcher.delivery;

import com.ledgerline.dispatcher.config.DispatcherProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * POSTs webhooks with the JDK HttpClient, which does its work on virtual threads.
 *
 * <p>{@code orTimeout} is the hard limit: it fails the call {@code http-timeout} after it started,
 * whatever stage it is in (connect, waiting for headers, a merchant trickling the body). A plain
 * request timeout would only cover waiting for the response headers.
 */
@Component
public class WebhookHttpClient {

    private final HttpClient httpClient;
    private final Duration timeout;

    public WebhookHttpClient(DispatcherProperties properties) {
        this.timeout = properties.httpTimeout();
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1) // no h2c upgrade attempt on plain HTTP
                .build();
    }

    /**
     * @return the HTTP status code
     * @throws WebhookTimeoutException if there is no complete answer within the timeout
     * @throws WebhookDeliveryException if the connection fails
     */
    public int post(URI url, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);

        CompletableFuture<HttpResponse<Void>> call =
                httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
        try {
            return call.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join().statusCode();
        } catch (CompletionException e) {
            call.cancel(true); // stop the request instead of letting it run on in the background
            if (e.getCause() instanceof TimeoutException || e.getCause() instanceof HttpTimeoutException) {
                throw new WebhookTimeoutException("no answer from " + url + " within " + timeout, e.getCause());
            }
            throw new WebhookDeliveryException("could not reach " + url, e.getCause());
        }
    }
}

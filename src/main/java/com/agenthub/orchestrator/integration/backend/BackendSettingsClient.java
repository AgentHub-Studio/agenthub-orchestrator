package com.agenthub.orchestrator.integration.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.UUID;

/**
 * Client to fetch provider configuration from backend cadastros.
 */
@Component
public class BackendSettingsClient {

    private final WebClient webClient;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;

    public BackendSettingsClient(
        WebClient.Builder webClientBuilder,
        @Value("${agenthub.backend.base-url:http://agenthub-backend:8080}") String backendBaseUrl,
        @Value("${agenthub.backend.service-token:}") String serviceToken,
        @Value("${agenthub.backend.request-timeout-ms:3000}") long requestTimeoutMs,
        @Value("${agenthub.backend.max-retries:2}") int maxRetries,
        @Value("${agenthub.backend.retry-backoff-ms:250}") long retryBackoffMs
    ) {
        WebClient.Builder builder = webClientBuilder.baseUrl(backendBaseUrl);
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.webClient = builder.build();
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.maxRetries = maxRetries;
        this.retryBackoff = Duration.ofMillis(retryBackoffMs);
    }

    public BackendSettingsDTO getSettings(UUID tenantId) {
        BackendSettingsDTO settings = webClient.get()
            .uri("/api/settings")
            .header("X-Tenant-Id", tenantId.toString())
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, response ->
                response.bodyToMono(String.class).flatMap(body -> Mono.error(
                    new BackendSettingsException("Backend settings rejected request (4xx): " + truncate(body))
                ))
            )
            .onStatus(HttpStatusCode::is5xxServerError, response ->
                response.bodyToMono(String.class).flatMap(body -> Mono.error(
                    new BackendSettingsException("Backend settings unavailable (5xx): " + truncate(body))
                ))
            )
            .bodyToMono(BackendSettingsDTO.class)
            .timeout(requestTimeout)
            .retryWhen(
                Retry.fixedDelay(maxRetries, retryBackoff)
                    .filter(this::isRetriable)
            )
            .onErrorMap(this::wrapIfNeeded)
            .block();

        if (settings == null) {
            throw new BackendSettingsException("Backend settings response is empty");
        }
        return settings;
    }

    private boolean isRetriable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return !(throwable instanceof BackendSettingsException);
    }

    private Throwable wrapIfNeeded(Throwable throwable) {
        if (throwable instanceof BackendSettingsException) {
            return throwable;
        }
        return new BackendSettingsException("Failed to fetch backend settings", throwable);
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }
}

package com.agenthub.orchestrator.integration.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * Client to fetch provider configuration from backend cadastros.
 */
@Component
public class BackendSettingsClient {

    private final WebClient webClient;

    public BackendSettingsClient(
        WebClient.Builder webClientBuilder,
        @Value("${agenthub.backend.base-url:http://agenthub-backend:8080}") String backendBaseUrl,
        @Value("${agenthub.backend.service-token:}") String serviceToken
    ) {
        WebClient.Builder builder = webClientBuilder.baseUrl(backendBaseUrl);
        if (serviceToken != null && !serviceToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        }
        this.webClient = builder.build();
    }

    public BackendSettingsDTO getSettings(UUID tenantId) {
        return webClient.get()
            .uri("/api/settings")
            .header("X-Tenant-Id", tenantId.toString())
            .retrieve()
            .bodyToMono(BackendSettingsDTO.class)
            .block();
    }
}

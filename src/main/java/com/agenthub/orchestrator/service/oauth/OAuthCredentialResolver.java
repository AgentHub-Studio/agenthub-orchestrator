package com.agenthub.orchestrator.service.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves OAuth authentication headers by calling the backend internal API.
 * The orchestrator does not access the tenant database directly — it delegates
 * credential resolution to the backend service.
 */
@Component
public class OAuthCredentialResolver {

    private static final Logger logger = LoggerFactory.getLogger(OAuthCredentialResolver.class);

    private final WebClient backendClient;

    public OAuthCredentialResolver(
            WebClient.Builder builder,
            @Value("${agenthub.backend.base-url:http://agenthub-backend:8080}") String backendUrl) {
        this.backendClient = builder.baseUrl(backendUrl).build();
    }

    /**
     * Resolves authentication headers for the given OAuth credential ID.
     * Calls the backend's internal API which handles token acquisition and caching.
     *
     * @param credentialId the UUID of the OAuth credential
     * @param tenantId     the tenant ID for context resolution
     * @return a future with the resolved headers (e.g., {"Authorization": "Bearer ..."})
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, String>> resolveHeaders(String credentialId, UUID tenantId) {
        return backendClient.get()
                .uri("/api/internal/oauth-credentials/{id}/resolve-headers", credentialId)
                .header("X-Tenant-Id", tenantId.toString())
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> (Map<String, String>) map)
                .toFuture()
                .exceptionally(error -> {
                    logger.error("Failed to resolve OAuth headers for credential {}: {}",
                            credentialId, error.getMessage());
                    return Map.of();
                });
    }
}

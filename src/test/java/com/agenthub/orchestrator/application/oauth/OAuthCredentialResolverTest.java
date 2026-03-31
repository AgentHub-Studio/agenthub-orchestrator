package com.agenthub.orchestrator.application.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OAuthCredentialResolver.
 *
 * @since 1.0.0
 */
class OAuthCredentialResolverTest {

    @Test
    void shouldReturnEmptyMapWhenBackendIsUnavailable() throws Exception {
        OAuthCredentialResolver resolver = buildResolver("http://localhost:9997");

        CompletableFuture<Map<String, String>> future =
            resolver.resolveHeaders("cred-id-123", UUID.randomUUID());

        Map<String, String> result = future.get();

        // Fallback behavior: returns empty map on error
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldCompleteExceptionallyGracefully() throws Exception {
        OAuthCredentialResolver resolver = buildResolver("http://localhost:9997");

        String credentialId = UUID.randomUUID().toString();
        UUID tenantId = UUID.randomUUID();

        CompletableFuture<Map<String, String>> future = resolver.resolveHeaders(credentialId, tenantId);

        // Should complete (not hang), even if backend is unavailable
        assertNotNull(future.get());
    }

    @Test
    void shouldNotThrowWhenCalledWithValidParameters() {
        OAuthCredentialResolver resolver = buildResolver("http://localhost:9997");

        assertDoesNotThrow(() ->
            resolver.resolveHeaders("some-credential-id", UUID.randomUUID())
        );
    }

    @Test
    void shouldReturnFutureNotNull() {
        OAuthCredentialResolver resolver = buildResolver("http://localhost:9997");

        CompletableFuture<Map<String, String>> future =
            resolver.resolveHeaders("cred-abc", UUID.randomUUID());

        assertNotNull(future);
    }

    @Test
    void shouldHandleMultipleConcurrentCalls() throws Exception {
        OAuthCredentialResolver resolver = buildResolver("http://localhost:9997");
        UUID tenantId = UUID.randomUUID();

        CompletableFuture<Map<String, String>> f1 = resolver.resolveHeaders("cred-1", tenantId);
        CompletableFuture<Map<String, String>> f2 = resolver.resolveHeaders("cred-2", tenantId);
        CompletableFuture<Map<String, String>> f3 = resolver.resolveHeaders("cred-3", tenantId);

        Map<String, String> r1 = f1.get();
        Map<String, String> r2 = f2.get();
        Map<String, String> r3 = f3.get();

        // All should complete with empty map (fallback)
        assertNotNull(r1);
        assertNotNull(r2);
        assertNotNull(r3);
    }

    private OAuthCredentialResolver buildResolver(String backendUrl) {
        WebClient.Builder builder = WebClient.builder();
        return new OAuthCredentialResolver(builder, backendUrl);
    }
}

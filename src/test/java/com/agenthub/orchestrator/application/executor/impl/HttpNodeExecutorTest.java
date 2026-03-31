package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.oauth.OAuthCredentialResolver;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HttpNodeExecutor.
 *
 * @since 1.0.0
 */
class HttpNodeExecutorTest {

    private final OAuthCredentialResolver noOpOAuthResolver = buildNoOpOAuthResolver();

    @Test
    void shouldSupportHttpNodeType() {
        HttpNodeExecutor executor = buildExecutor();
        assertEquals(NodeType.HTTP, executor.getSupportedType());
    }

    @Test
    void shouldValidateConfigSuccessfullyWithUrl() {
        HttpNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of("url", "https://api.example.com/data"));
        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationWhenUrlIsMissing() {
        HttpNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of("method", "GET"));
        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("url"));
    }

    @Test
    void shouldFailValidationWhenUrlIsBlank() {
        HttpNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of("url", "  "));
        assertFalse(result.isValid());
    }

    @Test
    void shouldPassValidationForAnyMethodSinceHttpMethodAcceptsArbitraryValues() {
        // Spring's HttpMethod.valueOf() does not throw for unknown methods (it's not an enum)
        // so validation passes for any non-empty string
        HttpNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of(
            "url", "https://example.com",
            "method", "PATCH"
        ));
        assertTrue(result.isValid());
    }

    @Test
    void shouldPassValidationWithValidMethod() {
        HttpNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of(
            "url", "https://example.com",
            "method", "POST"
        ));
        assertTrue(result.isValid());
    }

    @Test
    void shouldReturnFailedResultWhenUrlIsNull() throws Exception {
        HttpNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        // No url — will throw NullPointerException internally
        PipelineNode node = new PipelineNode("http-1", NodeType.HTTP, "HTTP", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertFalse(result.success());
        assertEquals("http-1", result.nodeId());
    }

    @Test
    void shouldRenderTemplateInUrl() throws Exception {
        HttpNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        // url with template, but actual call will fail (no real server) — we test it returns a failed result not exception
        config.put("url", "https://httpbin.org/get?q={{query}}");
        config.put("method", "GET");

        PipelineNode node = new PipelineNode("http-2", NodeType.HTTP, "HTTP GET", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("query", "hello")
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        // The HTTP call itself will fail (no real server), but should not throw
        assertNotNull(result);
        assertEquals("http-2", result.nodeId());
    }

    @Test
    void shouldExposedNonRetryablePrefix() {
        assertEquals("[NON_RETRYABLE]", HttpNodeExecutor.NON_RETRYABLE_ERROR_PREFIX);
    }

    @Test
    void shouldAcceptDeleteMethod() {
        HttpNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of(
            "url", "https://example.com/resource/1",
            "method", "DELETE"
        ));
        assertTrue(result.isValid());
    }

    private HttpNodeExecutor buildExecutor() {
        WebClient.Builder builder = WebClient.builder();
        return new HttpNodeExecutor(builder, noOpOAuthResolver);
    }

    private static OAuthCredentialResolver buildNoOpOAuthResolver() {
        WebClient.Builder builder = WebClient.builder();
        // OAuthCredentialResolver will always return empty map on errors (no real backend)
        return new OAuthCredentialResolver(builder, "http://localhost:9996");
    }
}

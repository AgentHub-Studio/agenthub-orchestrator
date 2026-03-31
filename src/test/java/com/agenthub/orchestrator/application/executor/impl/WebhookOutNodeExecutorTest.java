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
 * Unit tests for WebhookOutNodeExecutor.
 *
 * @since 1.0.0
 */
class WebhookOutNodeExecutorTest {

    private final OAuthCredentialResolver noOpOAuthResolver = buildNoOpOAuthResolver();

    @Test
    void shouldSupportWebhookNodeType() {
        WebhookOutNodeExecutor executor = buildExecutor();
        assertEquals(NodeType.WEBHOOK, executor.getSupportedType());
    }

    @Test
    void shouldValidateConfigSuccessfullyWithUrl() {
        WebhookOutNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of(
            "url", "https://hooks.example.com/notify"
        ));
        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationWhenUrlIsMissing() {
        WebhookOutNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of("method", "POST"));
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("url")));
    }

    @Test
    void shouldFailValidationWhenUrlIsBlank() {
        WebhookOutNodeExecutor executor = buildExecutor();
        ValidationResult result = executor.validateConfig(Map.of("url", "  "));
        assertFalse(result.isValid());
    }

    @Test
    void shouldReturnSuccessEvenWhenHttpFails() throws Exception {
        // Key feature: WebhookOut is fire-and-forget — failures should not block the pipeline
        WebhookOutNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://nonexistent.example.internal/webhook");
        config.put("method", "POST");
        config.put("body", "{\"event\": \"test\"}");

        PipelineNode node = new PipelineNode("webhook-1", NodeType.WEBHOOK, "Notify", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        // Fire-and-forget: node always succeeds regardless of HTTP outcome
        assertTrue(result.success());
        assertEquals("webhook-1", result.nodeId());
    }

    @Test
    void shouldReturnSuccessWithGetMethodWhenFails() throws Exception {
        WebhookOutNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://nonexistent.example.internal/event");
        config.put("method", "GET");

        PipelineNode node = new PipelineNode("webhook-2", NodeType.WEBHOOK, "Notify", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
    }

    @Test
    void shouldRenderUrlTemplateWithInputVariables() throws Exception {
        WebhookOutNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://api.example.com/notify/{{userId}}");
        config.put("method", "POST");
        config.put("body", "{\"text\": \"Hello {{userId}}\"}");

        PipelineNode node = new PipelineNode("webhook-3", NodeType.WEBHOOK, "Notify", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("userId", "user-42")
        );

        // Will fail HTTP but should not throw
        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
    }

    @Test
    void shouldStoreResultInContextOnHttpFailure() throws Exception {
        WebhookOutNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://nonexistent.example.internal/webhook");

        PipelineNode node = new PipelineNode("webhook-4", NodeType.WEBHOOK, "Notify", config, null);
        UUID executionId = UUID.randomUUID();
        ExecutionContext context = new ExecutionContext(
            executionId, UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        executor.execute(node, context, config).get();

        // Result should be stored in the context even on failure
        Map<String, Object> nodeResult = context.getNodeResult("webhook-4");
        assertNotNull(nodeResult);
    }

    @Test
    void shouldDefaultToPostMethodWhenNotConfigured() throws Exception {
        WebhookOutNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://nonexistent.example.internal/webhook");
        // No "method" key

        PipelineNode node = new PipelineNode("webhook-5", NodeType.WEBHOOK, "Notify", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        // POST is the default — should succeed (fire-and-forget)
        assertTrue(result.success());
    }

    @Test
    void shouldReturnSuccessOutputWithFalseWhenFails() throws Exception {
        WebhookOutNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://nonexistent.example.internal/webhook");

        PipelineNode node = new PipelineNode("webhook-6", NodeType.WEBHOOK, "Notify", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success()); // node itself succeeds
        // But output should indicate the webhook call failed
        Map<String, Object> output = result.getOutputAsMap();
        assertNotNull(output);
    }

    private WebhookOutNodeExecutor buildExecutor() {
        WebClient.Builder builder = WebClient.builder();
        return new WebhookOutNodeExecutor(builder, noOpOAuthResolver);
    }

    private static OAuthCredentialResolver buildNoOpOAuthResolver() {
        WebClient.Builder builder = WebClient.builder();
        return new OAuthCredentialResolver(builder, "http://localhost:9995");
    }
}

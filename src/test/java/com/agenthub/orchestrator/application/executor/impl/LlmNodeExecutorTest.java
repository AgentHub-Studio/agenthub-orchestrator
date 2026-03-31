package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.llm.*;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LlmNodeExecutor.
 *
 * @since 1.0.0
 */
class LlmNodeExecutorTest {

    private static final String PROVIDER_NAME = "openai";

    private LlmConfigResolver llmConfigResolver;
    private LlmProvider llmProvider;
    private LlmProviderRegistry llmProviderRegistry;
    private LlmNodeExecutor executor;

    @BeforeEach
    void setUp() {
        ResolvedLlmConfig resolvedConfig = new ResolvedLlmConfig(
            PROVIDER_NAME, "gpt-4o", "https://api.openai.com", "sk-test", null, Map.of()
        );
        llmConfigResolver = (tenantId, config) -> resolvedConfig;

        llmProvider = new LlmProvider() {
            @Override
            public String getProviderName() {
                return PROVIDER_NAME;
            }

            @Override
            public CompletableFuture<LlmResponse> complete(LlmRequest request) {
                return CompletableFuture.completedFuture(
                    new LlmResponse("Hello from LLM!", 10, 20, "gpt-4o", PROVIDER_NAME)
                );
            }

            @Override
            public boolean validateConfig(Map<String, Object> config) {
                return true;
            }
        };

        llmProviderRegistry = new LlmProviderRegistry(List.of(llmProvider));
        executor = new LlmNodeExecutor(llmConfigResolver, llmProviderRegistry);
    }

    @Test
    void shouldSupportLlmNodeType() {
        assertEquals(NodeType.LLM, executor.getSupportedType());
    }

    @Test
    void shouldExecuteSuccessfullyAndStoreResult() throws Exception {
        PipelineNode node = new PipelineNode(
            "llm-1", NodeType.LLM, "My LLM", Map.of("prompt", "Say hello"), null
        );
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("text", "world")
        );

        NodeExecutionResult result = executor.execute(node, context, node.config()).get();

        assertTrue(result.success());
        assertEquals("llm-1", result.nodeId());
        Map<String, Object> output = result.getOutputAsMap();
        assertEquals("Hello from LLM!", output.get("response"));
        assertEquals("gpt-4o", output.get("model"));
        assertEquals(PROVIDER_NAME, output.get("provider"));
    }

    @Test
    void shouldStoreResultInContext() throws Exception {
        PipelineNode node = new PipelineNode(
            "llm-1", NodeType.LLM, "My LLM", Map.of("prompt", "Say hello"), null
        );
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        executor.execute(node, context, node.config()).get();

        Map<String, Object> nodeResult = context.getNodeResult("llm-1");
        assertNotNull(nodeResult);
        assertEquals("Hello from LLM!", nodeResult.get("response"));
    }

    @Test
    void shouldUseCustomOutputKey() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt", "Say hello");
        config.put("outputKey", "llmOutput");

        PipelineNode node = new PipelineNode("llm-1", NodeType.LLM, "My LLM", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();
        assertTrue(result.success());
        // The output should use the custom key
        Map<String, Object> output = result.getOutputAsMap();
        assertNotNull(output.get("llmOutput"));
    }

    @Test
    void shouldReturnFailedResultOnLlmError() throws Exception {
        llmProvider = new LlmProvider() {
            @Override
            public String getProviderName() {
                return PROVIDER_NAME;
            }

            @Override
            public CompletableFuture<LlmResponse> complete(LlmRequest request) {
                CompletableFuture<LlmResponse> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("API timeout"));
                return future;
            }

            @Override
            public boolean validateConfig(Map<String, Object> config) {
                return true;
            }
        };
        llmProviderRegistry = new LlmProviderRegistry(List.of(llmProvider));
        executor = new LlmNodeExecutor(llmConfigResolver, llmProviderRegistry);

        PipelineNode node = new PipelineNode(
            "llm-fail", NodeType.LLM, "Failing LLM", Map.of("prompt", "hello"), null
        );
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, node.config()).get();

        assertFalse(result.success());
        assertEquals("llm-fail", result.nodeId());
    }

    @Test
    void shouldFailWhenConfigResolverThrows() throws Exception {
        llmConfigResolver = (tenantId, config) -> {
            throw new IllegalArgumentException("Provider not configured");
        };
        executor = new LlmNodeExecutor(llmConfigResolver, llmProviderRegistry);

        PipelineNode node = new PipelineNode(
            "llm-bad", NodeType.LLM, "Bad LLM", Map.of("prompt", "hello"), null
        );
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, node.config()).get();

        assertFalse(result.success());
        assertEquals("llm-bad", result.nodeId());
    }

    @Test
    void shouldValidateConfigSuccessfullyWithPrompt() {
        ValidationResult result = executor.validateConfig(Map.of("prompt", "Do something"));

        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationWhenPromptIsMissing() {
        ValidationResult result = executor.validateConfig(Map.of("model", "gpt-4"));

        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void shouldRenderPromptWithInputVariables() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("prompt", "Hello {{name}}!");

        PipelineNode node = new PipelineNode("llm-1", NodeType.LLM, "LLM", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "World")
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
    }
}

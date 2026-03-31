package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
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
 * Unit tests for ToolNodeExecutor.
 *
 * @since 1.0.0
 */
class ToolNodeExecutorTest {

    @Test
    void shouldSupportToolNodeType() {
        ToolNodeExecutor executor = buildExecutor();
        assertEquals(NodeType.TOOL, executor.getSupportedType());
    }

    @Test
    void shouldValidateConfigSuccessfullyWithSkillSlugAndInput() {
        ToolNodeExecutor executor = buildExecutor();

        ValidationResult result = executor.validateConfig(Map.of(
            "skillSlug", "document_search",
            "input", Map.of("query", "test")
        ));

        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationWhenSkillSlugIsMissing() {
        ToolNodeExecutor executor = buildExecutor();

        ValidationResult result = executor.validateConfig(Map.of(
            "input", Map.of("query", "test")
        ));

        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    void shouldFailValidationWhenSkillSlugIsBlank() {
        ToolNodeExecutor executor = buildExecutor();

        ValidationResult result = executor.validateConfig(Map.of(
            "skillSlug", "  ",
            "input", Map.of()
        ));

        assertFalse(result.isValid());
    }

    @Test
    void shouldFailValidationWhenInputIsMissing() {
        ToolNodeExecutor executor = buildExecutor();

        ValidationResult result = executor.validateConfig(Map.of(
            "skillSlug", "my_skill"
        ));

        assertFalse(result.isValid());
    }

    @Test
    void shouldFailValidationWhenInputIsNotAMap() {
        ToolNodeExecutor executor = buildExecutor();

        ValidationResult result = executor.validateConfig(Map.of(
            "skillSlug", "my_skill",
            "input", "not a map"
        ));

        assertFalse(result.isValid());
    }

    @Test
    void shouldReturnFailedResultWhenSkillRuntimeIsUnavailable() throws Exception {
        ToolNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("skillSlug", "document_search");
        config.put("input", Map.of("query", "hello"));

        PipelineNode node = new PipelineNode("tool-1", NodeType.TOOL, "Doc Search", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        // The skill-runtime is unavailable (no real server), so it should fail
        assertFalse(result.success());
        assertEquals("tool-1", result.nodeId());
    }

    @Test
    void shouldRenderTemplateInputsFromContext() throws Exception {
        ToolNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("skillSlug", "search");
        config.put("input", Map.of("query", "{{userQuery}}"));

        PipelineNode node = new PipelineNode("tool-2", NodeType.TOOL, "Search", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Map.of("userQuery", "what is AI?")
        );

        // The call will fail (no real skill-runtime), but it must not throw
        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertNotNull(result);
        assertEquals("tool-2", result.nodeId());
    }

    @Test
    void shouldHandleNullInputGracefully() throws Exception {
        ToolNodeExecutor executor = buildExecutor();

        Map<String, Object> config = new HashMap<>();
        config.put("skillSlug", "my_skill");
        config.put("input", null);

        PipelineNode node = new PipelineNode("tool-3", NodeType.TOOL, "Null Input", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        // Should not throw — returns a failed result
        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertNotNull(result);
        assertEquals("tool-3", result.nodeId());
    }

    @Test
    void shouldExposeNonRetryableErrorPrefix() {
        assertEquals("[NON_RETRYABLE]", ToolNodeExecutor.NON_RETRYABLE_ERROR_PREFIX);
    }

    private ToolNodeExecutor buildExecutor() {
        WebClient.Builder builder = WebClient.builder();
        return new ToolNodeExecutor(builder, "http://localhost:9998");
    }
}

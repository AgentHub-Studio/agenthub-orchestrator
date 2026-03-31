package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransformNodeExecutor.
 *
 * @since 1.0.0
 */
class TransformNodeExecutorTest {

    private TransformNodeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TransformNodeExecutor();
    }

    @Test
    void shouldSupportTransformNodeType() {
        assertEquals(NodeType.TRANSFORM, executor.getSupportedType());
    }

    // --- JSONPath ---

    @Test
    void shouldApplyJsonPathToSourceNode() throws Exception {
        PipelineNode node = new PipelineNode("transform-1", NodeType.TRANSFORM, "Transform", Map.of(
            "type", "jsonpath",
            "source", "input-node",
            "sourceKey", "output",
            "jsonPath", "$.name",
            "outputKey", "extracted"
        ), null);

        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );
        context.setNodeResult("input-node", Map.of("output", Map.of("name", "Alice", "age", 30)));

        NodeExecutionResult result = executor.execute(node, context, node.config()).get();

        assertTrue(result.success());
        assertEquals("Alice", result.output());
    }

    @Test
    void shouldApplyJsonPathToInputKey() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jsonpath",
            "inputKey", "data",
            "jsonPath", "$.value",
            "outputKey", "extracted"
        );

        PipelineNode node = new PipelineNode("transform-2", NodeType.TRANSFORM, "Transform", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Map.of("data", Map.of("value", 42))
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals(42, result.output());
    }

    @Test
    void shouldFailJsonPathWhenSourceNodeMissing() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jsonpath",
            "source", "nonexistent-node",
            "sourceKey", "output",
            "jsonPath", "$.name"
        );

        PipelineNode node = new PipelineNode("transform-3", NodeType.TRANSFORM, "Transform", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertFalse(result.success());
    }

    // --- Template ---

    @Test
    void shouldApplyTemplateTransformation() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "template",
            "template", "Hello, {{name}}!"
        );

        PipelineNode node = new PipelineNode("transform-4", NodeType.TRANSFORM, "Template", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "Bob")
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals("Hello, Bob!", result.output());
    }

    @Test
    void shouldApplyTemplateWithNodeResultPlaceholder() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "template",
            "template", "Result: {{node.prev-node.value}}"
        );

        PipelineNode node = new PipelineNode("transform-5", NodeType.TRANSFORM, "Template", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );
        context.setNodeResult("prev-node", Map.of("value", "awesome"));

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals("Result: awesome", result.output());
    }

    // --- JQ subset ---

    @Test
    void shouldApplyJqDotIdentity() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jq",
            "query", ".",
            "inputKey", "data"
        );

        PipelineNode node = new PipelineNode("transform-6", NodeType.TRANSFORM, "JQ", config, null);
        Map<String, Object> inputData = Map.of("x", 1, "y", 2);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of("data", inputData)
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals(inputData, result.output());
    }

    @Test
    void shouldApplyJqFieldAccess() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jq",
            "query", ".name",
            "inputKey", "person"
        );

        PipelineNode node = new PipelineNode("transform-7", NodeType.TRANSFORM, "JQ", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Map.of("person", Map.of("name", "Carol", "age", 25))
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals("Carol", result.output());
    }

    @Test
    void shouldApplyJqNestedFieldAccess() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jq",
            "query", ".address.city",
            "inputKey", "user"
        );

        PipelineNode node = new PipelineNode("transform-8", NodeType.TRANSFORM, "JQ", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Map.of("user", Map.of("address", Map.of("city", "São Paulo")))
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals("São Paulo", result.output());
    }

    // --- Validation ---

    @Test
    void shouldPassValidationForJsonPathWithJsonPath() {
        ValidationResult result = executor.validateConfig(Map.of(
            "type", "jsonpath",
            "jsonPath", "$.name"
        ));
        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationForJsonPathMissingExpression() {
        ValidationResult result = executor.validateConfig(Map.of("type", "jsonpath"));
        assertFalse(result.isValid());
    }

    @Test
    void shouldPassValidationForTemplateWithTemplate() {
        ValidationResult result = executor.validateConfig(Map.of(
            "type", "template",
            "template", "Hello, {{name}}"
        ));
        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationForTemplateMissingTemplate() {
        ValidationResult result = executor.validateConfig(Map.of("type", "template"));
        assertFalse(result.isValid());
    }

    @Test
    void shouldPassValidationForJqWithQuery() {
        ValidationResult result = executor.validateConfig(Map.of(
            "type", "jq",
            "query", ".name"
        ));
        assertTrue(result.isValid());
    }

    @Test
    void shouldFailValidationForJqMissingQuery() {
        ValidationResult result = executor.validateConfig(Map.of("type", "jq"));
        assertFalse(result.isValid());
    }

    @Test
    void shouldFailValidationForUnsupportedType() {
        ValidationResult result = executor.validateConfig(Map.of("type", "unknown_transform"));
        assertFalse(result.isValid());
    }

    @Test
    void shouldDefaultToJsonPathTypeWhenMissing() {
        ValidationResult result = executor.validateConfig(Map.of("jsonPath", "$.field"));
        assertTrue(result.isValid());
    }

    @Test
    void shouldFailExecutionForUnsupportedTransformType() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("type", "unsupported_type");

        PipelineNode node = new PipelineNode("transform-x", NodeType.TRANSFORM, "Bad Transform", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertFalse(result.success());
    }

    @Test
    void shouldUseFullInputWhenNoSourceOrInputKeySpecified() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jq",
            "query", "."
        );

        PipelineNode node = new PipelineNode("transform-full", NodeType.TRANSFORM, "JQ Full", config, null);
        Map<String, Object> inputMap = Map.of("a", 1, "b", 2);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), inputMap
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals(inputMap, result.output());
    }

    @Test
    void shouldStoreResultInContextWithCustomOutputKey() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "template",
            "template", "Hello!",
            "outputKey", "greeting"
        );

        PipelineNode node = new PipelineNode("transform-ctx", NodeType.TRANSFORM, "Template", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Map.of()
        );

        executor.execute(node, context, config).get();

        Map<String, Object> nodeResult = context.getNodeResult("transform-ctx");
        assertNotNull(nodeResult);
        assertEquals("Hello!", nodeResult.get("greeting"));
    }

    @Test
    void shouldApplyListIndexAccessInJq() throws Exception {
        Map<String, Object> config = Map.of(
            "type", "jq",
            "query", ".items.0",
            "inputKey", "data"
        );

        PipelineNode node = new PipelineNode("transform-list", NodeType.TRANSFORM, "JQ List", config, null);
        ExecutionContext context = new ExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            Map.of("data", Map.of("items", List.of("first", "second", "third")))
        );

        NodeExecutionResult result = executor.execute(node, context, config).get();

        assertTrue(result.success());
        assertEquals("first", result.output());
    }
}

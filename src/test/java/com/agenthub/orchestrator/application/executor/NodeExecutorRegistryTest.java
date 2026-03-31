package com.agenthub.orchestrator.application.executor;

import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import com.agenthub.orchestrator.domain.exception.UnsupportedNodeTypeException;
import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeExecutorRegistry.
 *
 * @since 1.0.0
 */
class NodeExecutorRegistryTest {

    @Test
    void shouldRegisterAndReturnExecutorByType() {
        NodeExecutor inputExecutor = stubExecutor(NodeType.INPUT);
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(inputExecutor));

        NodeExecutor found = registry.getExecutor(NodeType.INPUT);

        assertSame(inputExecutor, found);
    }

    @Test
    void shouldThrowUnsupportedNodeTypeExceptionForUnregisteredType() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
            stubExecutor(NodeType.INPUT)
        ));

        assertThrows(UnsupportedNodeTypeException.class,
            () -> registry.getExecutor(NodeType.OUTPUT));
    }

    @Test
    void shouldReturnTrueForRegisteredType() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
            stubExecutor(NodeType.LLM)
        ));

        assertTrue(registry.hasExecutor(NodeType.LLM));
    }

    @Test
    void shouldReturnFalseForUnregisteredType() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
            stubExecutor(NodeType.INPUT)
        ));

        assertFalse(registry.hasExecutor(NodeType.OUTPUT));
    }

    @Test
    void shouldReturnAllSupportedNodeTypes() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(
            stubExecutor(NodeType.INPUT),
            stubExecutor(NodeType.OUTPUT),
            stubExecutor(NodeType.TRANSFORM)
        ));

        Set<NodeType> types = registry.getSupportedNodeTypes();

        assertEquals(3, types.size());
        assertTrue(types.contains(NodeType.INPUT));
        assertTrue(types.contains(NodeType.OUTPUT));
        assertTrue(types.contains(NodeType.TRANSFORM));
    }

    @Test
    void shouldRegisterMultipleExecutors() {
        NodeExecutor inputEx = stubExecutor(NodeType.INPUT);
        NodeExecutor llmEx = stubExecutor(NodeType.LLM);
        NodeExecutor httpEx = stubExecutor(NodeType.HTTP);

        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(inputEx, llmEx, httpEx));

        assertSame(inputEx, registry.getExecutor(NodeType.INPUT));
        assertSame(llmEx, registry.getExecutor(NodeType.LLM));
        assertSame(httpEx, registry.getExecutor(NodeType.HTTP));
    }

    @Test
    void shouldHandleEmptyExecutorList() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of());

        assertTrue(registry.getSupportedNodeTypes().isEmpty());
        assertFalse(registry.hasExecutor(NodeType.LLM));
    }

    @Test
    void shouldOverwriteWhenSameTypeRegisteredTwice() {
        NodeExecutor first = stubExecutor(NodeType.INPUT);
        NodeExecutor second = stubExecutor(NodeType.INPUT);

        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of(first, second));

        NodeExecutor found = registry.getExecutor(NodeType.INPUT);
        // Last one registered wins
        assertSame(second, found);
    }

    @Test
    void shouldExceptionMessageContainNodeType() {
        NodeExecutorRegistry registry = new NodeExecutorRegistry(List.of());

        UnsupportedNodeTypeException ex = assertThrows(
            UnsupportedNodeTypeException.class,
            () -> registry.getExecutor(NodeType.FOREACH)
        );

        assertTrue(ex.getMessage().contains("FOREACH"));
    }

    private static NodeExecutor stubExecutor(NodeType type) {
        return new NodeExecutor() {
            @Override
            public NodeType getSupportedType() {
                return type;
            }

            @Override
            public CompletableFuture<NodeExecutionResult> execute(
                    PipelineNode node, ExecutionContext context, Map<String, Object> config) {
                return CompletableFuture.completedFuture(NodeExecutionResult.success(node.id(), Map.of()));
            }

            @Override
            public ValidationResult validateConfig(Map<String, Object> config) {
                return ValidationResult.success();
            }
        };
    }
}

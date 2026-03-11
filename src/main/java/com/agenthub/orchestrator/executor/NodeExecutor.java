package com.agenthub.orchestrator.executor;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for all node executors
 * 
 * Each node type has its own executor implementation that knows how to
 * execute that specific node type.
 * 
 * Executors are stateless and thread-safe.
 * 
 * @since 1.0.0
 */
public interface NodeExecutor {
    
    /**
     * Get the node type this executor supports
     * 
     * @return Node type (INPUT, OUTPUT, TRANSFORM, LLM, etc.)
     */
    NodeType getSupportedType();
    
    /**
     * Execute a node
     * 
     * @param node The node to execute
     * @param context Execution context (thread-safe, mutable)
     * @param config Node configuration
     * @return Future with execution result
     */
    CompletableFuture<NodeExecutionResult> execute(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config
    );
    
    /**
     * Validate node configuration
     * 
     * Called during pipeline validation (before execution).
     * 
     * @param config Node configuration to validate
     * @return Validation result
     */
    ValidationResult validateConfig(Map<String, Object> config);
}

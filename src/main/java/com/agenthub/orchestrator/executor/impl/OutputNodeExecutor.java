package com.agenthub.orchestrator.executor.impl;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.executor.ExecutionContext;
import com.agenthub.orchestrator.executor.NodeExecutionResult;
import com.agenthub.orchestrator.executor.NodeExecutor;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for OUTPUT nodes
 * 
 * OUTPUT node is the exit point of a pipeline.
 * It collects the final result from context and sets it as the pipeline output.
 * 
 * Configuration:
 * - outputKey: Key in context to use as output (default: "output")
 * - source: Node ID to get output from (alternative to outputKey)
 * 
 * Behavior:
 * - Reads value from context (either by key or from specific node result)
 * - Sets it as the final output
 * - Returns the output value
 * 
 * @since 1.0.0
 */
@Component
public class OutputNodeExecutor implements NodeExecutor {
    
    private static final Logger logger = LoggerFactory.getLogger(OutputNodeExecutor.class);
    
    @Override
    public NodeType getSupportedType() {
        return NodeType.OUTPUT;
    }
    
    @Override
    public CompletableFuture<NodeExecutionResult> execute(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config
    ) {
        logger.debug("Executing OUTPUT node: {}", node.id());
        
        try {
            Object output;
            
            // Option 1: Get output from specific node
            if (config.containsKey("source")) {
                String sourceNodeId = (String) config.get("source");
                String sourceKey = (String) config.getOrDefault("sourceKey", "output");
                output = context.getNodeResultValue(sourceNodeId, sourceKey);

                if (output == null) {
                    Map<String, Object> sourceResult = context.getNodeResult(sourceNodeId);
                    if (sourceResult != null && sourceResult.get("output") instanceof Map<?, ?> nested) {
                        output = nested.get(sourceKey);
                    }
                }

                logger.debug("OUTPUT node {} using source: {} key: {}", node.id(), sourceNodeId, sourceKey);
            }
            // Option 2: Get output by key from input
            else {
                String outputKey = (String) config.getOrDefault("outputKey", "output");
                output = context.getInputValue(outputKey);
                logger.debug("OUTPUT node {} using outputKey: {}", node.id(), outputKey);
            }
            
            // Set as final output
            context.setFinalOutput(output);
            Map<String, Object> outputMap = new HashMap<>();
            outputMap.put("output", output);
            context.setNodeResult(node.id(), outputMap);
            
            logger.debug("OUTPUT node {} completed with output: {}", node.id(), output);
            
            return CompletableFuture.completedFuture(
                NodeExecutionResult.success(node.id(), output)
            );
            
        } catch (Exception e) {
            logger.error("OUTPUT node {} failed", node.id(), e);
            return CompletableFuture.completedFuture(
                NodeExecutionResult.failed(node.id(), e)
            );
        }
    }
    
    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        // OUTPUT node should have either outputKey or source
        boolean hasOutputKey = config.containsKey("outputKey");
        boolean hasSource = config.containsKey("source");
        
        if (!hasOutputKey && !hasSource) {
            return ValidationResult.failure(
                List.of("OUTPUT node must have either 'outputKey' or 'source' configuration")
            );
        }
        
        return ValidationResult.success();
    }
}

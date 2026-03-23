package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.executor.NodeExecutor;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for INPUT nodes
 * 
 * INPUT node is the entry point of a pipeline.
 * It simply passes the initial input to the context.
 * 
 * Behavior:
 * - Takes initial input from context
 * - Optionally validates schema (if configured)
 * - Outputs the input unchanged
 * 
 * @since 1.0.0
 */
@Component
public class InputNodeExecutor implements NodeExecutor {
    
    private static final Logger logger = LoggerFactory.getLogger(InputNodeExecutor.class);
    
    @Override
    public NodeType getSupportedType() {
        return NodeType.INPUT;
    }
    
    @Override
    public CompletableFuture<NodeExecutionResult> execute(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config
    ) {
        logger.debug("Executing INPUT node: {}", node.id());
        
        try {
            // Get initial input
            Map<String, Object> input = context.getInput();
            
            // TODO: Schema validation if configured
            // if (config.containsKey("schema")) {
            //     validateSchema(input, config.get("schema"));
            // }
            
            // Store in context
            context.setNodeResult(node.id(), Map.of("output", input));
            
            logger.debug("INPUT node {} completed with input: {}", node.id(), input);
            
            return CompletableFuture.completedFuture(
                NodeExecutionResult.success(node.id(), input)
            );
            
        } catch (Exception e) {
            logger.error("INPUT node {} failed", node.id(), e);
            return CompletableFuture.completedFuture(
                NodeExecutionResult.failed(node.id(), e)
            );
        }
    }
    
    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        // INPUT node has minimal configuration requirements
        return ValidationResult.success();
    }
}

package com.agenthub.orchestrator.executor.impl;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.executor.ExecutionContext;
import com.agenthub.orchestrator.executor.NodeExecutionResult;
import com.agenthub.orchestrator.executor.NodeExecutor;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for TRANSFORM nodes
 * 
 * TRANSFORM node applies transformations to data using various methods:
 * - JSONPath extraction
 * - Template rendering (TODO)
 * - JQ queries (TODO)
 * 
 * Configuration:
 * - type: "jsonpath" | "template" | "jq"
 * - inputKey: Key in context to transform (or source node ID)
 * - outputKey: Key to store result
 * - jsonPath: JSONPath expression (for type=jsonpath)
 * - template: Template string (for type=template)
 * - query: JQ query (for type=jq)
 * 
 * @since 1.0.0
 */
@Component
public class TransformNodeExecutor implements NodeExecutor {
    
    private static final Logger logger = LoggerFactory.getLogger(TransformNodeExecutor.class);
    
    @Override
    public NodeType getSupportedType() {
        return NodeType.TRANSFORM;
    }
    
    @Override
    public CompletableFuture<NodeExecutionResult> execute(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config
    ) {
        logger.debug("Executing TRANSFORM node: {}", node.id());
        
        try {
            String transformType = (String) config.getOrDefault("type", "jsonpath");
            
            Object result = switch (transformType) {
                case "jsonpath" -> executeJsonPath(node, context, config);
                case "template" -> executeTemplate(node, context, config);
                case "jq" -> executeJq(node, context, config);
                default -> throw new UnsupportedOperationException(
                    "Unsupported transform type: " + transformType
                );
            };
            
            // Store result
            String outputKey = (String) config.getOrDefault("outputKey", "output");
            context.setNodeResult(node.id(), Map.of(outputKey, result));
            
            logger.debug("TRANSFORM node {} completed with type: {}", node.id(), transformType);
            
            return CompletableFuture.completedFuture(
                NodeExecutionResult.success(node.id(), result)
            );
            
        } catch (Exception e) {
            logger.error("TRANSFORM node {} failed", node.id(), e);
            return CompletableFuture.completedFuture(
                NodeExecutionResult.failed(node.id(), e)
            );
        }
    }
    
    /**
     * Execute JSONPath transformation
     */
    private Object executeJsonPath(PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        // Get input
        Object input = getInput(context, config);
        
        // Get JSONPath expression
        String jsonPath = (String) config.get("jsonPath");
        if (jsonPath == null) {
            throw new IllegalArgumentException("JSONPath expression is required for type=jsonpath");
        }
        
        // Apply JSONPath
        Object result = JsonPath.read(input, jsonPath);
        
        logger.debug("JSONPath {} applied to node {}: result type = {}", 
            jsonPath, node.id(), result != null ? result.getClass().getSimpleName() : "null");
        
        return result;
    }
    
    /**
     * Execute template transformation (TODO)
     */
    private Object executeTemplate(PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        // TODO: Implement template rendering (Mustache/Handlebars)
        String template = (String) config.get("template");
        if (template == null) {
            throw new IllegalArgumentException("Template is required for type=template");
        }
        
        // For now, just return the template
        logger.warn("Template transformation not yet implemented for node {}", node.id());
        return template;
    }
    
    /**
     * Execute JQ transformation (TODO)
     */
    private Object executeJq(PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        // TODO: Implement JQ query execution
        String query = (String) config.get("query");
        if (query == null) {
            throw new IllegalArgumentException("JQ query is required for type=jq");
        }
        
        logger.warn("JQ transformation not yet implemented for node {}", node.id());
        throw new UnsupportedOperationException("JQ transformation not yet implemented");
    }
    
    /**
     * Get input from context based on configuration
     */
    private Object getInput(ExecutionContext context, Map<String, Object> config) {
        // Option 1: Get from specific node result
        if (config.containsKey("source")) {
            String sourceNodeId = (String) config.get("source");
            String sourceKey = (String) config.getOrDefault("sourceKey", "output");
            Object input = context.getNodeResultValue(sourceNodeId, sourceKey);
            if (input == null) {
                throw new IllegalStateException(
                    "No result found for source node: " + sourceNodeId + " key: " + sourceKey
                );
            }
            return input;
        }
        
        // Option 2: Get from input by key
        if (config.containsKey("inputKey")) {
            String inputKey = (String) config.get("inputKey");
            Object input = context.getInputValue(inputKey);
            if (input == null) {
                throw new IllegalStateException("No input found for key: " + inputKey);
            }
            return input;
        }
        
        // Default: Use entire input
        return context.getInput();
    }
    
    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        String type = (String) config.get("type");
        if (type == null) {
            type = "jsonpath"; // Default
        }
        
        // Validate based on type
        return switch (type) {
            case "jsonpath" -> {
                if (!config.containsKey("jsonPath")) {
                    yield ValidationResult.failure(
                        List.of("JSONPath expression is required for type=jsonpath")
                    );
                }
                yield ValidationResult.success();
            }
            case "template" -> {
                if (!config.containsKey("template")) {
                    yield ValidationResult.failure(
                        List.of("Template is required for type=template")
                    );
                }
                yield ValidationResult.success();
            }
            case "jq" -> {
                if (!config.containsKey("query")) {
                    yield ValidationResult.failure(
                        List.of("JQ query is required for type=jq")
                    );
                }
                yield ValidationResult.success();
            }
            default -> ValidationResult.failure(
                List.of("Unsupported transform type: " + type)
            );
        };
    }
}

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for TRANSFORM nodes
 * 
 * TRANSFORM node applies transformations to data using various methods:
 * - JSONPath extraction
 * - Template rendering
 * - Lightweight JQ subset (. , .field, .field.nested)
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
            Map<String, Object> output = new HashMap<>();
            output.put(outputKey, result);
            context.setNodeResult(node.id(), output);
            
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
     * Execute template transformation.
     */
    private Object executeTemplate(PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        String template = (String) config.get("template");
        if (template == null) {
            throw new IllegalArgumentException("Template is required for type=template");
        }

        String rendered = renderTemplate(template, context);
        logger.debug("Template transformation applied for node {}", node.id());
        return rendered;
    }

    /**
     * Execute a lightweight JQ subset:
     * - "." returns full input
     * - ".a" returns map key a
     * - ".a.b" returns nested map keys
     */
    private Object executeJq(PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        String query = (String) config.get("query");
        if (query == null) {
            throw new IllegalArgumentException("JQ query is required for type=jq");
        }

        Object input = getInput(context, config);
        Object result = applyJqSubset(input, query);
        logger.debug("JQ subset query '{}' applied to node {}", query, node.id());
        return result;
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

    private String renderTemplate(String template, ExecutionContext context) {
        String rendered = template;

        for (Map.Entry<String, Object> entry : context.getInput().entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            rendered = rendered.replace("{{input." + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            rendered = rendered.replace("${context.input." + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }

        for (Map.Entry<String, Map<String, Object>> nodeEntry : context.getAllNodeResults().entrySet()) {
            String nodeId = nodeEntry.getKey();
            Map<String, Object> nodeMap = nodeEntry.getValue();
            for (Map.Entry<String, Object> valueEntry : nodeMap.entrySet()) {
                String key = valueEntry.getKey();
                String value = String.valueOf(valueEntry.getValue());
                rendered = rendered.replace("{{node." + nodeId + "." + key + "}}", value);
                rendered = rendered.replace("${context.nodeResults." + nodeId + "." + key + "}", value);
            }
        }

        return rendered;
    }

    private Object applyJqSubset(Object input, String query) {
        String trimmed = query.trim();
        if (".".equals(trimmed)) {
            return input;
        }
        if (!trimmed.startsWith(".")) {
            throw new IllegalArgumentException("Unsupported jq subset query: " + query);
        }

        String[] tokens = trimmed.substring(1).split("\\.");
        List<String> path = new ArrayList<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                path.add(token);
            }
        }

        Object current = input;
        for (String token : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(token);
                continue;
            }
            if (current instanceof List<?> list) {
                int idx;
                try {
                    idx = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("List access requires numeric token in jq subset: " + token);
                }
                if (idx < 0 || idx >= list.size()) {
                    throw new IllegalArgumentException("List index out of bounds in jq subset: " + idx);
                }
                current = list.get(idx);
                continue;
            }

            throw new IllegalArgumentException("Cannot navigate token '" + token + "' on non-container value");
        }

        return current;
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

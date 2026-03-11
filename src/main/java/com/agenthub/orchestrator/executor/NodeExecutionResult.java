package com.agenthub.orchestrator.executor;

import java.util.Map;

/**
 * Result of a node execution
 * 
 * Immutable value object.
 * 
 * @param nodeId ID of the executed node
 * @param success Whether execution succeeded
 * @param output Output data (if successful)
 * @param error Error message (if failed)
 * 
 * @since 1.0.0
 */
public record NodeExecutionResult(
    String nodeId,
    boolean success,
    Object output,
    String error
) {
    
    /**
     * Create a successful result
     */
    public static NodeExecutionResult success(String nodeId, Object output) {
        return new NodeExecutionResult(nodeId, true, output, null);
    }
    
    /**
     * Create a failed result
     */
    public static NodeExecutionResult failed(String nodeId, String error) {
        return new NodeExecutionResult(nodeId, false, null, error);
    }
    
    /**
     * Create a failed result from exception
     */
    public static NodeExecutionResult failed(String nodeId, Throwable throwable) {
        String error = throwable.getMessage() != null 
            ? throwable.getMessage() 
            : throwable.getClass().getSimpleName();
        return new NodeExecutionResult(nodeId, false, null, error);
    }
    
    /**
     * Get output as map (if it is a map)
     */
    public Map<String, Object> getOutputAsMap() {
        if (output instanceof Map) {
            return (Map<String, Object>) output;
        }
        return Map.of("value", output);
    }
}

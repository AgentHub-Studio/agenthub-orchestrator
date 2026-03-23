package com.agenthub.orchestrator.application.executor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe execution context for pipeline execution
 * 
 * Stores:
 * - Initial input (read-only)
 * - Execution metadata (read-only)
 * - Node results (read-write, namespaced by node ID)
 * - Final output
 * 
 * Context mutation follows the rules defined in AGENTS.md:
 * - Each node writes to its own namespace: context.nodeResults[nodeId]
 * - Input and execution metadata are read-only
 * - Variables can be resolved using ${context.nodeResults.nodeId.key} syntax
 * 
 * @since 1.0.0
 */
public class ExecutionContext {
    
    private final UUID executionId;
    private final UUID tenantId;
    private final UUID agentId;
    private final Map<String, Object> input; // Read-only
    private final Map<String, Map<String, Object>> nodeResults; // Write per-node
    private volatile Object finalOutput;
    
    public ExecutionContext(UUID executionId, UUID tenantId, UUID agentId, Map<String, Object> input) {
        this.executionId = executionId;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.input = Map.copyOf(input != null ? input : Map.of());
        this.nodeResults = new ConcurrentHashMap<>();
    }
    
    /**
     * Get execution ID
     */
    public UUID getExecutionId() {
        return executionId;
    }
    
    /**
     * Get tenant ID
     */
    public UUID getTenantId() {
        return tenantId;
    }
    
    /**
     * Get agent ID
     */
    public UUID getAgentId() {
        return agentId;
    }
    
    /**
     * Get initial input (read-only)
     */
    public Map<String, Object> getInput() {
        return input;
    }
    
    /**
     * Get a value from input
     */
    public Object getInputValue(String key) {
        return input.get(key);
    }
    
    /**
     * Set node result (thread-safe)
     * 
     * Each node writes to its own namespace.
     * 
     * @param nodeId Node ID
     * @param result Result data
     */
    public void setNodeResult(String nodeId, Map<String, Object> result) {
        nodeResults.put(nodeId, new ConcurrentHashMap<>(result));
    }
    
    /**
     * Get node result (thread-safe)
     * 
     * @param nodeId Node ID
     * @return Node result or null if not found
     */
    public Map<String, Object> getNodeResult(String nodeId) {
        return nodeResults.get(nodeId);
    }
    
    /**
     * Get a specific value from a node result
     * 
     * @param nodeId Node ID
     * @param key Key within node result
     * @return Value or null if not found
     */
    public Object getNodeResultValue(String nodeId, String key) {
        Map<String, Object> result = nodeResults.get(nodeId);
        return result != null ? result.get(key) : null;
    }
    
    /**
     * Get all node results
     */
    public Map<String, Map<String, Object>> getAllNodeResults() {
        return Map.copyOf(nodeResults);
    }
    
    /**
     * Set final output
     * 
     * Typically set by OUTPUT node.
     */
    public void setFinalOutput(Object output) {
        this.finalOutput = output;
    }
    
    /**
     * Get final output
     */
    public Object getFinalOutput() {
        return finalOutput;
    }
    
    /**
     * Resolve a variable reference
     * 
     * Syntax: ${context.input.key} or ${context.nodeResults.nodeId.key}
     * 
     * @param reference Variable reference
     * @return Resolved value or null
     */
    public Object resolveVariable(String reference) {
        if (reference == null || !reference.startsWith("${") || !reference.endsWith("}")) {
            return reference; // Not a variable reference
        }
        
        String path = reference.substring(2, reference.length() - 1);
        String[] parts = path.split("\\.");
        
        if (parts.length < 2) {
            return null;
        }
        
        // context.input.key
        if ("context".equals(parts[0]) && "input".equals(parts[1])) {
            if (parts.length == 3) {
                return input.get(parts[2]);
            }
        }
        
        // context.nodeResults.nodeId.key
        if ("context".equals(parts[0]) && "nodeResults".equals(parts[1]) && parts.length >= 4) {
            String nodeId = parts[2];
            String key = parts[3];
            return getNodeResultValue(nodeId, key);
        }
        
        return null;
    }
    
    /**
     * Convert context to JSON-serializable map
     */
    public Map<String, Object> toMap() {
        return Map.of(
            "executionId", executionId.toString(),
            "tenantId", tenantId.toString(),
            "agentId", agentId.toString(),
            "input", input,
            "nodeResults", Map.copyOf(nodeResults)
        );
    }
}

package com.agenthub.orchestrator.domain.pipeline;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Represents a node in a pipeline DAG
 * 
 * Immutable value object.
 * 
 * Based on specification: PIPELINE_DAG.md
 * 
 * @param id Unique identifier for node within pipeline
 * @param type Type of node (LLM, HTTP, CONDITION, etc.)
 * @param name Human-readable name
 * @param config Node-specific configuration
 * @param position UI position (x, y coordinates)
 * 
 * @since 1.0.0
 */
public record PipelineNode(
    @JsonProperty("id") String id,
    @JsonProperty("type") NodeType type,
    @JsonProperty("name") String name,
    @JsonProperty("config") Map<String, Object> config,
    @JsonProperty("position") Position position
) {
    
    /**
     * Validation constructor
     */
    public PipelineNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Node id cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Node type cannot be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Node name cannot be null or blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("Node config cannot be null (use empty map)");
        }
    }
    
    /**
     * Get configuration value by key
     */
    public Object getConfigValue(String key) {
        return config.get(key);
    }
    
    /**
     * Get configuration value with type casting
     */
    @SuppressWarnings("unchecked")
    public <T> T getConfigValue(String key, Class<T> type) {
        Object value = config.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }
    
    /**
     * Check if configuration has key
     */
    public boolean hasConfig(String key) {
        return config.containsKey(key);
    }
    
    /**
     * UI position for visual editor
     */
    public record Position(
        @JsonProperty("x") int x,
        @JsonProperty("y") int y
    ) {}
}

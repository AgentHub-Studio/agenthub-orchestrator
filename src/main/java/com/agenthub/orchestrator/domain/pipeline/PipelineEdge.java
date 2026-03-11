package com.agenthub.orchestrator.domain.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an edge (connection) between nodes in a pipeline DAG
 * 
 * Immutable value object.
 * 
 * Based on specification: PIPELINE_DAG.md
 * 
 * @param id Unique identifier for edge
 * @param sourceNodeId ID of source node
 * @param targetNodeId ID of target node
 * @param sourcePort Output port of source node (e.g., "success", "error", "output")
 * @param targetPort Input port of target node (typically "input")
 * @param conditionLabel Optional label for conditional edges
 * 
 * @since 1.0.0
 */
public record PipelineEdge(
    @JsonProperty("id") String id,
    @JsonProperty("sourceNodeId") String sourceNodeId,
    @JsonProperty("targetNodeId") String targetNodeId,
    @JsonProperty("sourcePort") String sourcePort,
    @JsonProperty("targetPort") String targetPort,
    @JsonProperty("conditionLabel") String conditionLabel
) {
    
    /**
     * Validation constructor
     */
    public PipelineEdge {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Edge id cannot be null or blank");
        }
        if (sourceNodeId == null || sourceNodeId.isBlank()) {
            throw new IllegalArgumentException("Source node id cannot be null or blank");
        }
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw new IllegalArgumentException("Target node id cannot be null or blank");
        }
        if (sourceNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException("Edge cannot connect node to itself");
        }
    }
    
    /**
     * Check if edge is conditional (has condition label)
     */
    public boolean isConditional() {
        return conditionLabel != null && !conditionLabel.isBlank();
    }
    
    /**
     * Check if edge has specific port
     */
    public boolean hasSourcePort(String port) {
        return sourcePort != null && sourcePort.equals(port);
    }
}

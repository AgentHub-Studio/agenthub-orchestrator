package com.agenthub.orchestrator.domain.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Result of a node execution
 * 
 * Immutable value object stored in execution context.
 * 
 * Based on specification: PIPELINE_DAG.md - Context Mutation
 * 
 * @param nodeId ID of the node that executed
 * @param status Execution status
 * @param data Result data (varies by node type)
 * @param error Error message if failed
 * @param latencyMs Execution time in milliseconds
 * @param startedAt When execution started
 * @param completedAt When execution completed
 * @param attemptNumber Retry attempt number (1-based)
 * 
 * @since 1.0.0
 */
public record NodeResult(
    @JsonProperty("nodeId") String nodeId,
    @JsonProperty("status") NodeExecutionStatus status,
    @JsonProperty("data") Object data,
    @JsonProperty("error") String error,
    @JsonProperty("latencyMs") Long latencyMs,
    @JsonProperty("startedAt") OffsetDateTime startedAt,
    @JsonProperty("completedAt") OffsetDateTime completedAt,
    @JsonProperty("attemptNumber") Integer attemptNumber
) {
    
    /**
     * Create successful result
     */
    public static NodeResult success(
        String nodeId,
        Object data,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int attemptNumber
    ) {
        long latencyMs = java.time.Duration.between(startedAt, completedAt).toMillis();
        return new NodeResult(
            nodeId,
            NodeExecutionStatus.COMPLETED,
            data,
            null,
            latencyMs,
            startedAt,
            completedAt,
            attemptNumber
        );
    }
    
    /**
     * Create failed result
     */
    public static NodeResult failure(
        String nodeId,
        String error,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int attemptNumber
    ) {
        long latencyMs = java.time.Duration.between(startedAt, completedAt).toMillis();
        return new NodeResult(
            nodeId,
            NodeExecutionStatus.FAILED,
            null,
            error,
            latencyMs,
            startedAt,
            completedAt,
            attemptNumber
        );
    }
    
    /**
     * Create skipped result (conditional branch not taken)
     */
    public static NodeResult skipped(String nodeId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new NodeResult(
            nodeId,
            NodeExecutionStatus.SKIPPED,
            null,
            null,
            0L,
            now,
            now,
            0
        );
    }
    
    /**
     * Check if node execution was successful
     */
    public boolean isSuccess() {
        return status == NodeExecutionStatus.COMPLETED;
    }
    
    /**
     * Check if node execution failed
     */
    public boolean isFailed() {
        return status == NodeExecutionStatus.FAILED;
    }
    
    /**
     * Get data as specific type (with casting)
     */
    @SuppressWarnings("unchecked")
    public <T> T getDataAs(Class<T> type) {
        if (data == null) {
            return null;
        }
        return (T) data;
    }
    
    /**
     * Get data as Map (common for most node types)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataAsMap() {
        return getDataAs(Map.class);
    }
}

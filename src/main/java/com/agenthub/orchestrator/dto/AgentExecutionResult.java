package com.agenthub.orchestrator.dto;

import com.agenthub.orchestrator.domain.execution.ExecutionStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Result of an agent execution
 * 
 * @param executionId Unique execution ID
 * @param status Final execution status
 * @param output Output data from the pipeline
 * @param error Error message if failed
 * @param startedAt When execution started
 * @param completedAt When execution completed
 * @param latencyMs Total execution time
 * 
 * @since 1.0.0
 */
public record AgentExecutionResult(
    UUID executionId,
    ExecutionStatus status,
    Map<String, Object> output,
    String error,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    Long latencyMs
) {
    
    /**
     * Check if execution was successful
     */
    public boolean isSuccess() {
        return status == ExecutionStatus.COMPLETED;
    }
    
    /**
     * Check if execution failed
     */
    public boolean isFailed() {
        return status == ExecutionStatus.FAILED 
            || status == ExecutionStatus.TIMED_OUT;
    }
}

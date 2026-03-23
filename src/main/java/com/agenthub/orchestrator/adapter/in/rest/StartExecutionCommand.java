package com.agenthub.orchestrator.adapter.in.rest;

import java.util.Map;
import java.util.UUID;

/**
 * Command to start an agent execution
 * 
 * @param tenantId Tenant ID (for multi-tenancy)
 * @param agentId Agent to execute
 * @param agentVersionId Specific version to execute (null = use active version)
 * @param input Input data for the pipeline
 * @param mode Execution mode (ASYNC or SYNC)
 * @param timeoutMs Maximum execution time in milliseconds
 * @param triggeredBy User who triggered the execution
 * 
 * @since 1.0.0
 */
public record StartExecutionCommand(
    UUID tenantId,
    UUID agentId,
    UUID agentVersionId,
    Map<String, Object> input,
    ExecutionMode mode,
    Long timeoutMs,
    UUID triggeredBy
) {
    
    /**
     * Validation constructor
     */
    public StartExecutionCommand {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant ID cannot be null");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("Agent ID cannot be null");
        }
        if (input == null) {
            input = Map.of(); // Default to empty map
        }
        if (mode == null) {
            mode = ExecutionMode.ASYNC; // Default to async
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 1800000L; // Default 30 minutes
        }
    }
    
    public enum ExecutionMode {
        /**
         * Asynchronous execution - returns immediately with execution ID
         */
        ASYNC,
        
        /**
         * Synchronous execution - waits for completion and returns result
         */
        SYNC
    }
}

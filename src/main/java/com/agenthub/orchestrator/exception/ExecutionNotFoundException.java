package com.agenthub.orchestrator.exception;

import java.util.UUID;

/**
 * Exception thrown when execution is not found
 * 
 * @since 1.0.0
 */
public class ExecutionNotFoundException extends RuntimeException {
    
    private final UUID executionId;
    
    public ExecutionNotFoundException(UUID executionId) {
        super("Execution not found: " + executionId);
        this.executionId = executionId;
    }
    
    public ExecutionNotFoundException(UUID executionId, String message) {
        super(message);
        this.executionId = executionId;
    }
    
    public UUID getExecutionId() {
        return executionId;
    }
}

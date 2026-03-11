package com.agenthub.orchestrator.domain.execution;

/**
 * Status of a single node execution
 * 
 * @since 1.0.0
 */
public enum NodeExecutionStatus {
    
    /**
     * Node is waiting for dependencies
     */
    PENDING,
    
    /**
     * Node is currently executing
     */
    RUNNING,
    
    /**
     * Node completed successfully
     */
    COMPLETED,
    
    /**
     * Node failed with error
     */
    FAILED,
    
    /**
     * Node was skipped (conditional branch not taken)
     */
    SKIPPED,
    
    /**
     * Node execution was cancelled
     */
    CANCELLED;
    
    /**
     * Check if node is in terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED 
            || this == FAILED 
            || this == SKIPPED 
            || this == CANCELLED;
    }
    
    /**
     * Check if node was successful
     */
    public boolean isSuccess() {
        return this == COMPLETED;
    }
}

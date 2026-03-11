package com.agenthub.orchestrator.domain.execution;

/**
 * Status of an agent execution
 * 
 * State machine:
 * PENDING → QUEUED → RUNNING → {COMPLETED | FAILED | CANCELLED | TIMED_OUT}
 *                   ↓
 *           {WAITING_TOOL | WAITING_CHILD_JOB}
 *                   ↓
 *               RUNNING
 * 
 * @since 1.0.0
 */
public enum ExecutionStatus {
    
    /**
     * Execution requested but not yet queued
     */
    PENDING,
    
    /**
     * Execution queued for processing
     */
    QUEUED,
    
    /**
     * Execution in progress
     */
    RUNNING,
    
    /**
     * Waiting for tool/skill execution to complete
     */
    WAITING_TOOL,
    
    /**
     * Waiting for child job/agent to complete
     */
    WAITING_CHILD_JOB,
    
    /**
     * Execution completed successfully
     */
    COMPLETED,
    
    /**
     * Execution failed with error
     */
    FAILED,
    
    /**
     * Execution cancelled by user
     */
    CANCELLED,
    
    /**
     * Execution exceeded timeout
     */
    TIMED_OUT;
    
    /**
     * Check if execution is in a terminal state
     */
    public boolean isTerminal() {
        return this == COMPLETED 
            || this == FAILED 
            || this == CANCELLED 
            || this == TIMED_OUT;
    }
    
    /**
     * Check if execution is in progress (active)
     */
    public boolean isActive() {
        return this == RUNNING 
            || this == WAITING_TOOL 
            || this == WAITING_CHILD_JOB;
    }
    
    /**
     * Check if execution can be cancelled
     */
    public boolean isCancellable() {
        return this == PENDING 
            || this == QUEUED 
            || this == RUNNING 
            || this == WAITING_TOOL 
            || this == WAITING_CHILD_JOB;
    }
    
    /**
     * Check if execution was successful
     */
    public boolean isSuccess() {
        return this == COMPLETED;
    }
    
    /**
     * Check if execution had an error
     */
    public boolean isError() {
        return this == FAILED 
            || this == TIMED_OUT;
    }
}

package com.agenthub.orchestrator.adapter.in.rest;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection for node execution summary
 * 
 * Performance: Returns only essential fields for timeline views.
 * Follows ADR-001: Use projections instead of full entities.
 * 
 * @since 1.0.0
 */
public interface NodeExecutionSummary {
    
    UUID getId();
    
    UUID getExecutionId();
    
    String getNodeType();
    
    String getNodeName();
    
    String getStatus();
    
    Instant getStartedAt();
    
    Instant getFinishedAt();
    
    Long getLatencyMs();
    
    String getErrorMessage();
    
    /**
     * Calculated field: is node still running
     */
    default boolean isRunning() {
        return "RUNNING".equals(getStatus());
    }
    
    /**
     * Calculated field: did node fail
     */
    default boolean isFailed() {
        return "FAILED".equals(getStatus());
    }
}

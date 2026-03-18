package com.agenthub.orchestrator.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection for execution summary (list view)
 * 
 * Performance: Returns only essential fields for list views.
 * Follows ADR-001: Use projections instead of full entities.
 * 
 * @since 1.0.0
 */
public interface ExecutionSummary {
    
    UUID getId();

    UUID getAgentId();
    
    UUID getAgentVersionId();
    
    String getStatus();
    
    String getTriggerType();
    
    Instant getStartedAt();
    
    Instant getFinishedAt();
    
    Instant getCreatedAt();
    
    /**
     * Calculated field: execution duration in milliseconds
     */
    default Long getDurationMs() {
        if (getStartedAt() == null || getFinishedAt() == null) {
            return null;
        }
        return getFinishedAt().toEpochMilli() - getStartedAt().toEpochMilli();
    }
    
    /**
     * Calculated field: is execution still running
     */
    default boolean isRunning() {
        String status = getStatus();
        return "RUNNING".equals(status) || "PENDING".equals(status);
    }
}

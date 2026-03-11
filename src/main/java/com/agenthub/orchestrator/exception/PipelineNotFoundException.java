package com.agenthub.orchestrator.exception;

import java.util.UUID;

/**
 * Exception thrown when pipeline definition is not found
 * 
 * @since 1.0.0
 */
public class PipelineNotFoundException extends RuntimeException {
    
    private final UUID pipelineId;
    
    public PipelineNotFoundException(UUID pipelineId) {
        super("Pipeline not found: " + pipelineId);
        this.pipelineId = pipelineId;
    }
    
    public PipelineNotFoundException(UUID pipelineId, String message) {
        super(message);
        this.pipelineId = pipelineId;
    }
    
    public UUID getPipelineId() {
        return pipelineId;
    }
}

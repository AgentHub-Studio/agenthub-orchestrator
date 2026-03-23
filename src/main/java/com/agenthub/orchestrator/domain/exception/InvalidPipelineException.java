package com.agenthub.orchestrator.domain.exception;

import java.util.List;

/**
 * Exception thrown when pipeline validation fails
 * 
 * @since 1.0.0
 */
public class InvalidPipelineException extends RuntimeException {
    
    private final List<String> validationErrors;
    
    public InvalidPipelineException(List<String> validationErrors) {
        super("Pipeline validation failed: " + String.join(", ", validationErrors));
        this.validationErrors = List.copyOf(validationErrors);
    }
    
    public InvalidPipelineException(String message) {
        super(message);
        this.validationErrors = List.of(message);
    }
    
    public List<String> getValidationErrors() {
        return validationErrors;
    }
}

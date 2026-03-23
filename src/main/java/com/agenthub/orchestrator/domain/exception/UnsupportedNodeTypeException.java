package com.agenthub.orchestrator.domain.exception;

/**
 * Exception thrown when trying to execute an unsupported node type
 * 
 * @since 1.0.0
 */
public class UnsupportedNodeTypeException extends RuntimeException {
    
    public UnsupportedNodeTypeException(String message) {
        super(message);
    }
    
    public UnsupportedNodeTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.agenthub.orchestrator.integration.backend;

/**
 * Exception raised when backend settings cannot be resolved.
 *
 * @since 1.0.0
 */
public class BackendSettingsException extends RuntimeException {

    public BackendSettingsException(String message) {
        super(message);
    }

    public BackendSettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}

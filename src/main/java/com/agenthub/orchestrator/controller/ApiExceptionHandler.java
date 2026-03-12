package com.agenthub.orchestrator.controller;

import com.agenthub.orchestrator.exception.ExecutionNotFoundException;
import com.agenthub.orchestrator.integration.backend.BackendSettingsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized API exception mapping.
 *
 * @since 1.0.0
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "validation_error");
        body.put("message", "Invalid request payload");
        body.put("details", ex.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    @ExceptionHandler(ExecutionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleExecutionNotFound(ExecutionNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "execution_not_found", ex.getMessage());
    }

    @ExceptionHandler(BackendSettingsException.class)
    public ResponseEntity<Map<String, Object>> handleBackendSettings(BackendSettingsException ex) {
        return error(HttpStatus.BAD_GATEWAY, "backend_settings_unavailable", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", error);
        body.put("message", message != null ? message : status.getReasonPhrase());
        return ResponseEntity.status(status).body(body);
    }
}

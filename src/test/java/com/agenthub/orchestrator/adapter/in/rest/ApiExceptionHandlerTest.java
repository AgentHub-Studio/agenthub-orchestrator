package com.agenthub.orchestrator.adapter.in.rest;

import com.agenthub.orchestrator.adapter.out.backend.BackendSettingsException;
import com.agenthub.orchestrator.domain.exception.ExecutionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiExceptionHandler.
 *
 * @since 1.0.0
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void shouldReturnBadRequestForIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("invalid value");

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("bad_request", response.getBody().get("error"));
        assertEquals("invalid value", response.getBody().get("message"));
    }

    @Test
    void shouldReturnNotFoundForExecutionNotFoundException() {
        UUID executionId = UUID.randomUUID();
        ExecutionNotFoundException ex = new ExecutionNotFoundException(executionId);

        ResponseEntity<Map<String, Object>> response = handler.handleExecutionNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("execution_not_found", response.getBody().get("error"));
        assertTrue(response.getBody().get("message").toString().contains(executionId.toString()));
    }

    @Test
    void shouldReturnBadGatewayForBackendSettingsException() {
        BackendSettingsException ex = new BackendSettingsException("backend unavailable");

        ResponseEntity<Map<String, Object>> response = handler.handleBackendSettings(ex);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("backend_settings_unavailable", response.getBody().get("error"));
        assertEquals("backend unavailable", response.getBody().get("message"));
    }

    @Test
    void shouldReturnValidationErrorForMethodArgumentNotValidException() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "name", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("validation_error", response.getBody().get("error"));
        assertEquals("Invalid request payload", response.getBody().get("message"));

        @SuppressWarnings("unchecked")
        List<String> details = (List<String>) response.getBody().get("details");
        assertNotNull(details);
        assertFalse(details.isEmpty());
        assertTrue(details.get(0).contains("name"));
    }

    @Test
    void shouldIncludeAllFieldErrorsInValidationDetails() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "field1", "error1"));
        bindingResult.addError(new FieldError("target", "field2", "error2"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidation(ex);

        @SuppressWarnings("unchecked")
        List<String> details = (List<String>) response.getBody().get("details");
        assertEquals(2, details.size());
    }

    @Test
    void shouldReturnReasonPhraseWhenIllegalArgumentMessageIsNull() {
        // IllegalArgumentException with null message
        IllegalArgumentException ex = new IllegalArgumentException((String) null);

        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody().get("message"));
    }

    @Test
    void shouldReturnCorrectStatusCodeForExecutionNotFoundWithCustomMessage() {
        UUID executionId = UUID.randomUUID();
        ExecutionNotFoundException ex = new ExecutionNotFoundException(executionId, "Custom message");

        ResponseEntity<Map<String, Object>> response = handler.handleExecutionNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Custom message", response.getBody().get("message"));
    }
}

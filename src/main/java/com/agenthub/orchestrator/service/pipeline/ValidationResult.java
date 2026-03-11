package com.agenthub.orchestrator.service.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of pipeline validation
 * 
 * Contains validation status and list of errors/warnings.
 * 
 * @since 1.0.0
 */
public class ValidationResult {
    
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;
    
    private ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
        this.valid = valid;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }
    
    /**
     * Create successful validation result
     */
    public static ValidationResult success() {
        return new ValidationResult(true, List.of(), List.of());
    }
    
    /**
     * Create successful validation with warnings
     */
    public static ValidationResult successWithWarnings(List<String> warnings) {
        return new ValidationResult(true, List.of(), warnings);
    }
    
    /**
     * Create failed validation result
     */
    public static ValidationResult failure(List<String> errors) {
        return new ValidationResult(false, errors, List.of());
    }
    
    /**
     * Create failed validation with warnings
     */
    public static ValidationResult failure(List<String> errors, List<String> warnings) {
        return new ValidationResult(false, errors, warnings);
    }
    
    public boolean isValid() {
        return valid;
    }
    
    public List<String> getErrors() {
        return errors;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ValidationResult{valid=").append(valid);
        if (!errors.isEmpty()) {
            sb.append(", errors=").append(errors);
        }
        if (!warnings.isEmpty()) {
            sb.append(", warnings=").append(warnings);
        }
        sb.append("}");
        return sb.toString();
    }
}

package com.agenthub.orchestrator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson ObjectMapper configuration
 * 
 * Configures JSON serialization/deserialization for:
 * - Pipeline definitions
 * - Execution state
 * - Node results
 * - DTOs
 * 
 * @since 1.0.0
 */
@Configuration
public class JacksonConfig {
    
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Register Java 8 date/time module (OffsetDateTime, etc.)
        mapper.registerModule(new JavaTimeModule());
        
        // Don't fail on unknown properties (forward compatibility)
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // Write dates as ISO-8601 strings, not timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // Don't include null values in JSON
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        
        // Pretty print for debugging (disable in production)
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        
        return mapper;
    }
}

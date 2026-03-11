package com.agenthub.orchestrator.service.llm;

import java.util.Map;

/**
 * LLM request
 * 
 * Unified request format for all LLM providers.
 * Each provider will map this to their specific API format.
 * 
 * @param modelId Model identifier (gpt-4, claude-3-5-sonnet, etc.)
 * @param prompt Prompt text
 * @param systemPrompt System prompt (optional)
 * @param config Provider-specific configuration (temperature, max_tokens, etc.)
 * @param connectionConfig Connection configuration (base_url, api_key, etc.)
 * 
 * @since 1.0.0
 */
public record LlmRequest(
    String modelId,
    String prompt,
    String systemPrompt,
    Map<String, Object> config,
    Map<String, Object> connectionConfig
) {
    
    /**
     * Get configuration value with default
     */
    public Object getConfig(String key, Object defaultValue) {
        return config != null ? config.getOrDefault(key, defaultValue) : defaultValue;
    }
    
    /**
     * Get temperature (default: 0.7)
     */
    public double getTemperature() {
        return ((Number) getConfig("temperature", 0.7)).doubleValue();
    }
    
    /**
     * Get max tokens (default: 4000)
     */
    public int getMaxTokens() {
        return ((Number) getConfig("max_tokens", 4000)).intValue();
    }
    
    /**
     * Get connection value
     */
    public Object getConnectionConfig(String key) {
        return connectionConfig != null ? connectionConfig.get(key) : null;
    }
    
    /**
     * Get base URL
     */
    public String getBaseUrl() {
        return (String) getConnectionConfig("base_url");
    }
    
    /**
     * Get API key (from connection config)
     */
    public String getApiKey() {
        return (String) getConnectionConfig("api_key");
    }
}

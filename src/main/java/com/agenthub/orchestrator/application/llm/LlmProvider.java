package com.agenthub.orchestrator.application.llm;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for LLM providers
 * 
 * Each provider (OpenAI, Anthropic, Ollama, etc.) implements this interface.
 * The provider is responsible for making the actual API call to the LLM service.
 * 
 * @since 1.0.0
 */
public interface LlmProvider {
    
    /**
     * Get provider name (OPENAI, ANTHROPIC, OLLAMA, etc.)
     */
    String getProviderName();
    
    /**
     * Generate completion
     * 
     * @param request LLM request with model, prompt, and config
     * @return Future with LLM response
     */
    CompletableFuture<LlmResponse> complete(LlmRequest request);
    
    /**
     * Validate provider configuration
     * 
     * @param config Provider-specific configuration
     * @return true if valid
     */
    boolean validateConfig(Map<String, Object> config);
    
    /**
     * Check if provider supports streaming
     */
    default boolean supportsStreaming() {
        return false;
    }
}

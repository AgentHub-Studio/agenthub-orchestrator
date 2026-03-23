package com.agenthub.orchestrator.application.llm;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry for LLM provider implementations.
 * <p>
 * Auto-discovers all {@link LlmProvider} beans and makes them available
 * by provider name (case-insensitive).
 * </p>
 *
 * @since 1.0.0
 */
@Component
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> providers = new HashMap<>();

    /**
     * Constructor that auto-registers all LLM provider beans.
     *
     * @param providerList list of all LlmProvider beans found by Spring
     */
    public LlmProviderRegistry(List<LlmProvider> providerList) {
        for (LlmProvider provider : providerList) {
            providers.put(provider.getProviderName().toLowerCase(Locale.ROOT), provider);
        }
    }

    /**
     * Retrieves a provider by name (case-insensitive).
     *
     * @param provider the provider name (e.g., "ollama", "openai", "anthropic")
     * @return the provider implementation
     * @throws IllegalArgumentException if provider is not registered
     */
    public LlmProvider get(String provider) {
        LlmProvider llmProvider = providers.get(provider.toLowerCase(Locale.ROOT));
        if (llmProvider == null) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return llmProvider;
    }
}

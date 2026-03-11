package com.agenthub.orchestrator.service.llm;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> providers = new HashMap<>();

    public LlmProviderRegistry(List<LlmProvider> providerList) {
        for (LlmProvider provider : providerList) {
            providers.put(provider.getProviderName().toLowerCase(Locale.ROOT), provider);
        }
    }

    public LlmProvider get(String provider) {
        LlmProvider llmProvider = providers.get(provider.toLowerCase(Locale.ROOT));
        if (llmProvider == null) {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return llmProvider;
    }
}

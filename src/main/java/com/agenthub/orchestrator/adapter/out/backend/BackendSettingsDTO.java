package com.agenthub.orchestrator.adapter.out.backend;

import java.util.List;

/**
 * DTO for backend settings response.
 *
 * Mirrors backend `/api/settings` response structure.
 */
public record BackendSettingsDTO(
    ProviderSettings openai,
    ProviderSettings ollama,
    ProviderSettings claude,
    ProviderSettings generic,
    GeneralSettings general
) {
    public record ProviderSettings(
        String apiKey,
        String model,
        List<String> models,
        String baseUrl,
        String organizationId,
        Double temperature,
        Double topP,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer maxTokens
    ) {}

    public record GeneralSettings(
        String language,
        String defaultProvider
    ) {}
}

package com.agenthub.orchestrator.application.llm;

import com.agenthub.orchestrator.adapter.out.backend.BackendSettingsClient;
import com.agenthub.orchestrator.adapter.out.backend.BackendSettingsDTO;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves runtime config using backend cadastros (source of truth).
 */
@Component
public class BackendLlmConfigResolver implements LlmConfigResolver {

    private final BackendSettingsClient backendSettingsClient;

    public BackendLlmConfigResolver(BackendSettingsClient backendSettingsClient) {
        this.backendSettingsClient = backendSettingsClient;
    }

    @Override
    public ResolvedLlmConfig resolve(UUID tenantId, Map<String, Object> nodeConfig) {
        BackendSettingsDTO settings = backendSettingsClient.getSettings(tenantId);
        String provider = normalizedProvider((String) nodeConfig.get("provider"));
        if (provider == null || provider.isBlank()) {
            provider = normalizedProvider(settings != null && settings.general() != null ? settings.general().defaultProvider() : "openai");
        }

        BackendSettingsDTO.ProviderSettings selected = selectProviderSettings(settings, provider);
        if (selected == null) {
            throw new IllegalArgumentException("Provider not configured in backend: " + provider);
        }

        String modelId = (String) nodeConfig.getOrDefault("model", selected.model());
        String baseUrl = (String) nodeConfig.getOrDefault("baseUrl", selected.baseUrl());
        String apiKey = (String) nodeConfig.getOrDefault("apiKey", selected.apiKey());
        String organizationId = (String) nodeConfig.getOrDefault("organizationId", selected.organizationId());

        Map<String, Object> params = new HashMap<>();
        putIfNotNull(params, "temperature", selected.temperature());
        putIfNotNull(params, "top_p", selected.topP());
        putIfNotNull(params, "frequency_penalty", selected.frequencyPenalty());
        putIfNotNull(params, "presence_penalty", selected.presencePenalty());
        putIfNotNull(params, "max_tokens", selected.maxTokens());

        Object nodeParamsObj = nodeConfig.get("parameters");
        if (nodeParamsObj instanceof Map<?, ?> nodeParams) {
            for (Map.Entry<?, ?> e : nodeParams.entrySet()) {
                if (e.getKey() != null) {
                    params.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }

        return new ResolvedLlmConfig(provider, modelId, baseUrl, apiKey, organizationId, params);
    }

    private BackendSettingsDTO.ProviderSettings selectProviderSettings(BackendSettingsDTO settings, String provider) {
        if (settings == null || provider == null) {
            return null;
        }
        return switch (provider) {
            case "openai" -> settings.openai();
            case "anthropic", "claude" -> settings.claude();
            case "ollama" -> settings.ollama();
            case "openrouter", "generic" -> settings.generic();
            default -> settings.generic();
        };
    }

    private String normalizedProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String normalized = provider.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "claude" -> "anthropic";
            default -> normalized;
        };
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}

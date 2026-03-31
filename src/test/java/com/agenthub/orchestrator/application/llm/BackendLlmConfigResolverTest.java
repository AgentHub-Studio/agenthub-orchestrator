package com.agenthub.orchestrator.application.llm;

import com.agenthub.orchestrator.adapter.out.backend.BackendSettingsClient;
import com.agenthub.orchestrator.adapter.out.backend.BackendSettingsDTO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BackendLlmConfigResolver.
 *
 * @since 1.0.0
 */
class BackendLlmConfigResolverTest {

    private static BackendSettingsDTO buildDefaultSettings() {
        BackendSettingsDTO.ProviderSettings openai = new BackendSettingsDTO.ProviderSettings(
            "sk-test-openai", "gpt-4o", List.of("gpt-4o"), "https://api.openai.com",
            null, 0.7, null, null, null, 4096
        );
        BackendSettingsDTO.ProviderSettings claude = new BackendSettingsDTO.ProviderSettings(
            "sk-test-anthropic", "claude-3-5-sonnet", List.of("claude-3-5-sonnet"),
            "https://api.anthropic.com", null, 0.7, null, null, null, 4096
        );
        BackendSettingsDTO.ProviderSettings ollama = new BackendSettingsDTO.ProviderSettings(
            null, "llama3", List.of("llama3"), "http://ollama:11434",
            null, 0.7, null, null, null, 4096
        );
        BackendSettingsDTO.ProviderSettings generic = new BackendSettingsDTO.ProviderSettings(
            "sk-test-generic", "gpt-4", List.of("gpt-4"), "https://openrouter.ai/api/v1",
            null, 0.7, null, null, null, 4096
        );
        return new BackendSettingsDTO(
            openai, ollama, claude, generic,
            new BackendSettingsDTO.GeneralSettings("en", "openai")
        );
    }

    @Test
    void shouldResolveOpenAiProviderFromNodeConfig() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "openai");

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("openai", resolved.provider());
        assertEquals("gpt-4o", resolved.modelId());
        assertEquals("sk-test-openai", resolved.apiKey());
    }

    @Test
    void shouldFallbackToDefaultProviderWhenNotSpecified() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        // No provider key — should use general.defaultProvider = "openai"

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("openai", resolved.provider());
    }

    @Test
    void shouldNormalizeClaudeToAnthropic() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "claude");

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("anthropic", resolved.provider());
        assertEquals("claude-3-5-sonnet", resolved.modelId());
    }

    @Test
    void shouldThrowWhenProviderSettingsAreMissing() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        // Settings without openai configured
        BackendSettingsDTO noOpenai = new BackendSettingsDTO(
            null, null, null, null,
            new BackendSettingsDTO.GeneralSettings("en", "openai")
        );
        when(client.getSettings(any())).thenReturn(noOpenai);
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "openai");

        assertThrows(IllegalArgumentException.class,
            () -> resolver.resolve(UUID.randomUUID(), config));
    }

    @Test
    void shouldMergeNodeParamsOverBackendDefaults() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "openai");
        config.put("parameters", Map.of("temperature", 0.9, "max_tokens", 1000));

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals(0.9, resolved.parameters().get("temperature"));
        assertEquals(1000, resolved.parameters().get("max_tokens"));
    }

    @Test
    void shouldOverrideModelFromNodeConfig() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "openai");
        config.put("model", "gpt-4-turbo");

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("gpt-4-turbo", resolved.modelId());
    }

    @Test
    void shouldResolveOllamaProvider() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "ollama");

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("ollama", resolved.provider());
        assertEquals("llama3", resolved.modelId());
    }

    @Test
    void shouldNormalizeProviderToLowercase() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "OPENAI");

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("openai", resolved.provider());
    }

    @Test
    void shouldUseBackendDefaultWhenProviderIsBlank() {
        BackendSettingsClient client = Mockito.mock(BackendSettingsClient.class);
        when(client.getSettings(any())).thenReturn(buildDefaultSettings());
        BackendLlmConfigResolver resolver = new BackendLlmConfigResolver(client);

        Map<String, Object> config = new HashMap<>();
        config.put("provider", "   ");

        ResolvedLlmConfig resolved = resolver.resolve(UUID.randomUUID(), config);

        assertEquals("openai", resolved.provider());
    }
}

package com.agenthub.orchestrator.application.llm;

import java.util.Map;

/**
 * Resolved LLM runtime config from backend cadastros + node overrides.
 */
public record ResolvedLlmConfig(
    String provider,
    String modelId,
    String baseUrl,
    String apiKey,
    String organizationId,
    Map<String, Object> parameters
) {}

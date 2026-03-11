package com.agenthub.orchestrator.service.llm;

/**
 * Normalized LLM response.
 */
public record LlmResponse(
    String text,
    Integer inputTokens,
    Integer outputTokens,
    String model,
    String provider
) {}

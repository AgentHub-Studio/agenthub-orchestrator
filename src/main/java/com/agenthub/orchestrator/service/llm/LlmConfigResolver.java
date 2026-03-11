package com.agenthub.orchestrator.service.llm;

import java.util.Map;
import java.util.UUID;

public interface LlmConfigResolver {
    ResolvedLlmConfig resolve(UUID tenantId, Map<String, Object> nodeConfig);
}

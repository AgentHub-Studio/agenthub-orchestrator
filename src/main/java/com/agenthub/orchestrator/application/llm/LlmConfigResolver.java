package com.agenthub.orchestrator.application.llm;

import java.util.Map;
import java.util.UUID;

/**
 * Resolves LLM configuration from node config, tenant defaults, and backend settings.
 * <p>
 * Resolution strategy:
 * <ol>
 *   <li>Node-level config (highest priority)</li>
 *   <li>Backend tenant settings</li>
 *   <li>System defaults (lowest priority)</li>
 * </ol>
 * </p>
 *
 * @since 1.0.0
 */
public interface LlmConfigResolver {
    
    /**
     * Resolves complete LLM configuration for a tenant and node.
     *
     * @param tenantId the tenant ID for tenant-specific settings
     * @param nodeConfig the node configuration from the pipeline
     * @return resolved configuration with provider, model, and parameters
     */
    ResolvedLlmConfig resolve(UUID tenantId, Map<String, Object> nodeConfig);
}

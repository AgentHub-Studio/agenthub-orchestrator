package com.agenthub.orchestrator.service.pipeline;

import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;

import java.util.UUID;

/**
 * Service for loading and validating pipeline definitions
 * 
 * Based on specification: PIPELINE_DAG.md, JAVA_INTERFACES.md
 * 
 * Responsibilities:
 * - Load pipeline definition from database (agent_version.pipeline_definition_json)
 * - Validate DAG structure (no cycles, no orphan nodes)
 * - Cache definitions for performance
 * - Convert JSON to domain model
 * 
 * @since 1.0.0
 */
public interface PipelineDefinitionService {
    
    /**
     * Load pipeline definition by ID
     * 
     * @param pipelineId Pipeline definition ID (usually agent_version_id)
     * @param tenantId Tenant ID for multi-tenancy
     * @return Pipeline definition
     * @throws PipelineNotFoundException if pipeline not found
     */
    PipelineDefinition loadPipelineDefinition(UUID pipelineId, UUID tenantId);
    
    /**
     * Load active pipeline definition for an agent
     * 
     * @param agentId Agent ID
     * @param tenantId Tenant ID for multi-tenancy
     * @return Active pipeline definition
     * @throws PipelineNotFoundException if no active version found
     */
    PipelineDefinition getActivePipeline(UUID agentId, UUID tenantId);
    
    /**
     * Validate pipeline structure
     * 
     * Checks:
     * - No cycles in DAG
     * - All nodes reachable from entry node
     * - No orphan nodes (disconnected from main graph)
     * - All edge references valid
     * - Entry node exists
     * 
     * @param pipeline Pipeline to validate
     * @return Validation result
     */
    ValidationResult validatePipeline(PipelineDefinition pipeline);
    
    /**
     * Invalidate cache for pipeline
     * 
     * Call this when pipeline definition is updated.
     * 
     * @param pipelineId Pipeline ID to invalidate
     */
    void invalidateCache(UUID pipelineId);
    
    /**
     * Clear all cache
     */
    void clearCache();
}

package com.agenthub.orchestrator.service.execution;

import com.agenthub.orchestrator.domain.execution.ExecutionState;
import com.agenthub.orchestrator.domain.execution.NodeResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing execution state
 * 
 * Based on specification: ARCHITECTURE.md, JAVA_INTERFACES.md
 * 
 * Responsibilities:
 * - Create and initialize execution state
 * - Persist state to database (execution_state table)
 * - Update context and node results
 * - Track node completion/failure
 * - Idempotency: cache and retrieve node results
 * 
 * @since 1.0.0
 */
public interface ExecutionStateService {
    
    /**
     * Create new execution state
     * 
     * @param tenantId Tenant ID
     * @param agentId Agent ID
     * @param agentVersionId Agent version ID
     * @param input Initial input data
     * @return New execution state
     */
    ExecutionState createExecution(
        UUID tenantId,
        UUID agentId,
        UUID agentVersionId,
        Map<String, Object> input
    );
    
    /**
     * Load execution state by ID
     * 
     * @param executionId Execution ID
     * @param tenantId Tenant ID (for multi-tenancy)
     * @return Execution state
     * @throws ExecutionNotFoundException if not found
     */
    ExecutionState loadExecution(UUID executionId, UUID tenantId);
    
    /**
     * Save execution state to database
     * 
     * Persists complete state including context, node results, tracking sets.
     * 
     * @param state Execution state to save
     */
    void saveExecution(ExecutionState state);
    
    /**
     * Update context with new key-value pair
     * 
     * @param executionId Execution ID
     * @param key Context key
     * @param value Context value
     */
    void updateContext(UUID executionId, String key, Object value);
    
    /**
     * Mark node as visited
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     */
    void markNodeVisited(UUID executionId, String nodeId);
    
    /**
     * Mark node as completed with result
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     * @param result Node execution result
     */
    void markNodeCompleted(UUID executionId, String nodeId, NodeResult result);
    
    /**
     * Mark node as failed with result
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     * @param result Node execution result (with error)
     */
    void markNodeFailed(UUID executionId, String nodeId, NodeResult result);
    
    /**
     * Mark node as skipped (conditional branch not taken)
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     */
    void markNodeSkipped(UUID executionId, String nodeId);
    
    /**
     * Increment retry attempt for node
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     * @return New attempt number
     */
    int incrementNodeAttempt(UUID executionId, String nodeId);
    
    /**
     * Get cached result for idempotent node execution
     * 
     * Idempotency key: executionId + nodeId + attemptNumber + inputHash
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     * @param attemptNumber Attempt number
     * @param inputHash Hash of input data
     * @return Cached result if exists
     */
    Optional<NodeResult> getCachedResult(
        UUID executionId,
        String nodeId,
        int attemptNumber,
        String inputHash
    );
    
    /**
     * Cache result for idempotent node execution
     * 
     * TTL: 24 hours
     * Storage: Redis (< 1MB) or MinIO (> 1MB)
     * 
     * @param executionId Execution ID
     * @param nodeId Node ID
     * @param attemptNumber Attempt number
     * @param inputHash Hash of input data
     * @param result Result to cache
     */
    void cacheResult(
        UUID executionId,
        String nodeId,
        int attemptNumber,
        String inputHash,
        NodeResult result
    );
    
    /**
     * Delete execution state (cleanup)
     * 
     * @param executionId Execution ID
     * @param tenantId Tenant ID
     */
    void deleteExecution(UUID executionId, UUID tenantId);
}

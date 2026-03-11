package com.agenthub.orchestrator.service.execution;

import com.agenthub.orchestrator.domain.execution.ExecutionState;
import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import com.agenthub.orchestrator.domain.execution.NodeExecutionStatus;
import com.agenthub.orchestrator.domain.execution.NodeResult;
import com.agenthub.orchestrator.entity.ExecutionEntity;
import com.agenthub.orchestrator.entity.NodeExecutionEntity;
import com.agenthub.orchestrator.exception.ExecutionNotFoundException;
import com.agenthub.orchestrator.repository.ExecutionRepository;
import com.agenthub.orchestrator.repository.NodeExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of ExecutionStateService
 * 
 * For Sprint 1 (foundation):
 * - In-memory storage (ConcurrentHashMap)
 * - No persistence yet (will be added in Sprint 2)
 * - No idempotency cache yet (will be added in Sprint 6)
 * 
 * Thread-safe for concurrent node execution.
 * 
 * @since 1.0.0
 */
@Service
public class ExecutionStateServiceImpl implements ExecutionStateService {
    
    private static final Logger log = LoggerFactory.getLogger(ExecutionStateServiceImpl.class);
    
    // In-memory storage (temporary - will be replaced with Redis/PostgreSQL)
    private final Map<UUID, ExecutionState> executionStore;
    
    private final ObjectMapper objectMapper;
    private final ExecutionRepository executionRepository;
    private final NodeExecutionRepository nodeExecutionRepository;

    @Autowired
    public ExecutionStateServiceImpl(
        ObjectMapper objectMapper,
        ExecutionRepository executionRepository,
        NodeExecutionRepository nodeExecutionRepository
    ) {
        this.objectMapper = objectMapper;
        this.executionRepository = executionRepository;
        this.nodeExecutionRepository = nodeExecutionRepository;
        this.executionStore = new ConcurrentHashMap<>();
    }
    
    public ExecutionStateServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.executionRepository = null;
        this.nodeExecutionRepository = null;
        this.executionStore = new ConcurrentHashMap<>();
    }
    
    @Override
    public ExecutionState createExecution(
        UUID tenantId,
        UUID agentId,
        UUID agentVersionId,
        Map<String, Object> input
    ) {
        log.debug("Creating execution: tenantId={}, agentId={}, agentVersionId={}", 
            tenantId, agentId, agentVersionId);
        
        UUID executionId = UUID.randomUUID();
        
        ExecutionState state = new ExecutionState(
            executionId,
            tenantId,
            agentId,
            agentVersionId,
            input
        );
        
        // Store in memory
        executionStore.put(executionId, state);
        
        log.info("Execution created: executionId={}, tenantId={}, agentId={}", 
            executionId, tenantId, agentId);
        
        return state;
    }
    
    @Override
    public ExecutionState loadExecution(UUID executionId, UUID tenantId) {
        log.debug("Loading execution: executionId={}, tenantId={}", executionId, tenantId);
        
        ExecutionState state = executionStore.get(executionId);
        
        if (state == null) {
            state = loadFromDatabase(executionId, tenantId)
                .orElseThrow(() -> new ExecutionNotFoundException(executionId));
            executionStore.put(executionId, state);
        }
        
        // Verify tenant ownership (multi-tenancy security)
        if (!state.getTenantId().equals(tenantId)) {
            throw new ExecutionNotFoundException(
                executionId,
                "Execution not found or access denied"
            );
        }
        
        return state;
    }
    
    @Override
    @Transactional
    public void saveExecution(ExecutionState state) {
        log.debug("Saving execution: executionId={}, status={}", 
            state.getExecutionId(), state.getStatus());
        
        // For now, just update in-memory store
        executionStore.put(state.getExecutionId(), state);
        
        if (executionRepository != null && nodeExecutionRepository != null) {
            persistExecution(state);
        }
        
        log.trace("Execution saved: executionId={}", state.getExecutionId());
    }
    
    @Override
    public void updateContext(UUID executionId, String key, Object value) {
        log.trace("Updating context: executionId={}, key={}", executionId, key);
        
        ExecutionState state = executionStore.get(executionId);
        if (state == null) {
            throw new ExecutionNotFoundException(executionId);
        }
        
        state.updateContext(key, value);
        saveExecution(state);
    }
    
    @Override
    public void markNodeVisited(UUID executionId, String nodeId) {
        log.debug("Marking node visited: executionId={}, nodeId={}", executionId, nodeId);
        
        ExecutionState state = executionStore.get(executionId);
        if (state == null) {
            throw new ExecutionNotFoundException(executionId);
        }
        
        state.markNodeVisited(nodeId);
        saveExecution(state);
    }
    
    @Override
    public void markNodeCompleted(UUID executionId, String nodeId, NodeResult result) {
        log.debug("Marking node completed: executionId={}, nodeId={}, latency={}ms", 
            executionId, nodeId, result.latencyMs());
        
        ExecutionState state = executionStore.get(executionId);
        if (state == null) {
            throw new ExecutionNotFoundException(executionId);
        }
        
        state.markNodeCompleted(nodeId, result);
        saveExecution(state);
        
        log.info("Node completed: executionId={}, nodeId={}, status={}", 
            executionId, nodeId, result.status());
    }
    
    @Override
    public void markNodeFailed(UUID executionId, String nodeId, NodeResult result) {
        log.warn("Marking node failed: executionId={}, nodeId={}, error={}", 
            executionId, nodeId, result.error());
        
        ExecutionState state = executionStore.get(executionId);
        if (state == null) {
            throw new ExecutionNotFoundException(executionId);
        }
        
        state.markNodeFailed(nodeId, result);
        saveExecution(state);
        
        log.error("Node failed: executionId={}, nodeId={}, error={}", 
            executionId, nodeId, result.error());
    }
    
    @Override
    public void markNodeSkipped(UUID executionId, String nodeId) {
        log.debug("Marking node skipped: executionId={}, nodeId={}", executionId, nodeId);
        
        ExecutionState state = executionStore.get(executionId);
        if (state == null) {
            throw new ExecutionNotFoundException(executionId);
        }
        
        state.markNodeSkipped(nodeId);
        saveExecution(state);
    }
    
    @Override
    public int incrementNodeAttempt(UUID executionId, String nodeId) {
        log.debug("Incrementing node attempt: executionId={}, nodeId={}", executionId, nodeId);
        
        ExecutionState state = executionStore.get(executionId);
        if (state == null) {
            throw new ExecutionNotFoundException(executionId);
        }
        
        state.incrementNodeAttempt(nodeId);
        int attemptNumber = state.getNodeAttemptCount(nodeId);
        
        saveExecution(state);
        
        log.debug("Node attempt incremented: executionId={}, nodeId={}, attempt={}", 
            executionId, nodeId, attemptNumber);
        
        return attemptNumber;
    }
    
    @Override
    public Optional<NodeResult> getCachedResult(
        UUID executionId,
        String nodeId,
        int attemptNumber,
        String inputHash
    ) {
        log.trace("Checking cached result: executionId={}, nodeId={}, attempt={}, hash={}", 
            executionId, nodeId, attemptNumber, inputHash);
        
        // TODO Sprint 6: Implement idempotency cache
        // - Check Redis for cached result
        // - Key format: "idempotency:{executionId}:{nodeId}:{attemptNumber}:{inputHash}"
        // - TTL: 24 hours
        // - If > 1MB, check MinIO instead
        
        return Optional.empty();
    }
    
    @Override
    public void cacheResult(
        UUID executionId,
        String nodeId,
        int attemptNumber,
        String inputHash,
        NodeResult result
    ) {
        log.trace("Caching result: executionId={}, nodeId={}, attempt={}, hash={}", 
            executionId, nodeId, attemptNumber, inputHash);
        
        // TODO Sprint 6: Implement idempotency cache
        // - Serialize result to JSON
        // - If size < 1MB: store in Redis with 24h TTL
        // - If size > 1MB: store in MinIO with 24h TTL
        // - Key format: "idempotency:{executionId}:{nodeId}:{attemptNumber}:{inputHash}"
    }
    
    @Override
    @Transactional
    public void deleteExecution(UUID executionId, UUID tenantId) {
        log.debug("Deleting execution: executionId={}, tenantId={}", executionId, tenantId);
        
        ExecutionState state = executionStore.get(executionId);
        
        if (state != null) {
            // Verify tenant ownership
            if (!state.getTenantId().equals(tenantId)) {
                throw new ExecutionNotFoundException(
                    executionId,
                    "Execution not found or access denied"
                );
            }
            
            executionStore.remove(executionId);
            
            log.info("Execution deleted: executionId={}", executionId);
        }
        
        if (executionRepository != null && nodeExecutionRepository != null) {
            executionRepository.findByIdAndTenantId(executionId, tenantId)
                .ifPresent(entity -> {
                    nodeExecutionRepository.deleteByExecution_Id(executionId);
                    executionRepository.delete(entity);
                });
        }

        // TODO Sprint 6: Delete cached results from Redis/MinIO
    }

    private void persistExecution(ExecutionState state) {
        ExecutionEntity executionEntity = executionRepository.findById(state.getExecutionId())
            .orElseGet(ExecutionEntity::new);

        executionEntity.setId(state.getExecutionId());
        executionEntity.setTenantId(state.getTenantId());
        executionEntity.setAgentId(state.getAgentId());
        executionEntity.setAgentVersionId(state.getAgentVersionId());
        executionEntity.setTriggerType(executionEntity.getTriggerType() != null ? executionEntity.getTriggerType() : "MANUAL");
        executionEntity.setStatus(state.getStatus().name());
        executionEntity.setInputJson(state.getInput());
        executionEntity.setContextJson(state.getContext());
        executionEntity.setResultJson(Map.of(
            "nodeResults", state.getNodeResults(),
            "completedNodes", state.getCompletedNodes(),
            "failedNodes", state.getFailedNodes(),
            "skippedNodes", state.getSkippedNodes()
        ));
        executionEntity.setStartedAt(toInstant(state.getStartedAt()));
        executionEntity.setFinishedAt(toInstant(state.getCompletedAt()));
        executionEntity.setErrorMessage(state.getError());

        ExecutionEntity savedExecution = executionRepository.save(executionEntity);

        nodeExecutionRepository.deleteByExecution_Id(savedExecution.getId());
        for (Map.Entry<String, NodeResult> entry : state.getNodeResults().entrySet()) {
            NodeResult result = entry.getValue();

            NodeExecutionEntity nodeEntity = new NodeExecutionEntity();
            nodeEntity.setExecution(savedExecution);
            nodeEntity.setNodeName(entry.getKey());
            nodeEntity.setNodeType("UNKNOWN");
            nodeEntity.setStatus(result.status().name());
            nodeEntity.setOutputJson(toOutputJson(result));
            nodeEntity.setStartedAt(toInstant(result.startedAt()));
            nodeEntity.setFinishedAt(toInstant(result.completedAt()));
            nodeEntity.setLatencyMs(result.latencyMs());
            nodeEntity.setErrorMessage(result.error());

            nodeExecutionRepository.save(nodeEntity);
        }
    }

    private Optional<ExecutionState> loadFromDatabase(UUID executionId, UUID tenantId) {
        if (executionRepository == null || nodeExecutionRepository == null) {
            return Optional.empty();
        }

        return executionRepository.findByIdAndTenantId(executionId, tenantId)
            .map(entity -> {
                ExecutionState state = new ExecutionState(
                    entity.getId(),
                    entity.getTenantId(),
                    entity.getAgentId(),
                    entity.getAgentVersionId(),
                    entity.getInputJson() != null ? entity.getInputJson() : Map.of()
                );

                restoreExecutionStatus(state, entity.getStatus(), entity.getErrorMessage());
                List<NodeExecutionEntity> nodeExecutions = nodeExecutionRepository.findByExecutionId(executionId);
                for (NodeExecutionEntity nodeEntity : nodeExecutions) {
                    String nodeId = nodeEntity.getNodeName();
                    NodeExecutionStatus nodeStatus = NodeExecutionStatus.valueOf(nodeEntity.getStatus());
                    NodeResult nodeResult = new NodeResult(
                        nodeId,
                        nodeStatus,
                        nodeEntity.getOutputJson(),
                        nodeEntity.getErrorMessage(),
                        nodeEntity.getLatencyMs(),
                        toOffsetDateTime(nodeEntity.getStartedAt()),
                        toOffsetDateTime(nodeEntity.getFinishedAt()),
                        1
                    );

                    state.markNodeVisited(nodeId);
                    switch (nodeStatus) {
                        case COMPLETED -> state.markNodeCompleted(nodeId, nodeResult);
                        case FAILED -> state.markNodeFailed(nodeId, nodeResult);
                        case SKIPPED -> state.markNodeSkipped(nodeId);
                        default -> {
                            // keep as visited only
                        }
                    }
                }

                return state;
            });
    }

    private void restoreExecutionStatus(ExecutionState state, String status, String errorMessage) {
        ExecutionStatus executionStatus = ExecutionStatus.valueOf(status);
        switch (executionStatus) {
            case PENDING -> {
                // default state
            }
            case QUEUED -> state.markAsQueued();
            case RUNNING -> state.markAsRunning();
            case WAITING_TOOL -> state.markAsWaitingTool();
            case WAITING_CHILD_JOB -> state.markAsWaitingChildJob();
            case COMPLETED -> state.markAsCompleted();
            case FAILED -> state.markAsFailed(errorMessage != null ? errorMessage : "Execution failed");
            case CANCELLED -> state.markAsCancelled();
            case TIMED_OUT -> state.markAsTimedOut();
        }
    }

    private Map<String, Object> toOutputJson(NodeResult result) {
        if (result.data() == null) {
            return Map.of();
        }
        if (result.data() instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            return casted;
        }
        return Map.of("value", result.data());
    }

    private Instant toInstant(OffsetDateTime dateTime) {
        return dateTime != null ? dateTime.toInstant() : null;
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
    
    /**
     * Get all executions (for debugging/testing)
     * TODO: Remove in production or add proper pagination
     */
    public Map<UUID, ExecutionState> getAllExecutions() {
        return Map.copyOf(executionStore);
    }
    
    /**
     * Clear all executions (for testing)
     */
    public void clearAll() {
        log.warn("Clearing all executions from memory");
        executionStore.clear();
    }
}

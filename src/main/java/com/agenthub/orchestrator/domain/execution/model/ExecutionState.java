package com.agenthub.orchestrator.domain.execution.model;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution state aggregate
 * 
 * Mutable aggregate root that tracks the runtime state of a pipeline execution.
 * Stored in execution_state table as JSONB.
 * 
 * Based on specification: PIPELINE_DAG.md, ARCHITECTURE.md
 * 
 * Thread-safe using ConcurrentHashMap for concurrent node execution.
 * 
 * @since 1.0.0
 */
public class ExecutionState {
    
    private final UUID executionId;
    private final UUID tenantId;
    private final UUID agentId;
    private final UUID agentVersionId;
    private final OffsetDateTime startedAt;
    
    private ExecutionStatus status;
    private OffsetDateTime completedAt;
    private String error;
    
    // Context: shared data between nodes
    private final Map<String, Object> context;
    
    // Node results: namespace per node (context.nodeResults[nodeId])
    private final Map<String, NodeResult> nodeResults;
    
    // Tracking sets for scheduling
    private final Set<String> visitedNodes;      // Nodes that have been visited
    private final Set<String> completedNodes;    // Nodes that completed successfully
    private final Set<String> failedNodes;       // Nodes that failed
    private final Set<String> skippedNodes;      // Nodes that were skipped
    
    // Retry tracking
    private final Map<String, Integer> nodeAttempts; // nodeId -> attempt count
    
    /**
     * Create new execution state
     */
    public ExecutionState(
        UUID executionId,
        UUID tenantId,
        UUID agentId,
        UUID agentVersionId,
        Map<String, Object> initialInput
    ) {
        this.executionId = Objects.requireNonNull(executionId, "Execution ID cannot be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID cannot be null");
        this.agentId = Objects.requireNonNull(agentId, "Agent ID cannot be null");
        this.agentVersionId = Objects.requireNonNull(agentVersionId, "Agent version ID cannot be null");
        this.startedAt = OffsetDateTime.now();
        this.status = ExecutionStatus.PENDING;
        
        // Initialize context with input and execution metadata
        this.context = new ConcurrentHashMap<>();
        this.context.put("input", initialInput != null ? initialInput : Map.of());
        this.context.put("execution", Map.of(
            "executionId", executionId.toString(),
            "tenantId", tenantId.toString(),
            "agentId", agentId.toString(),
            "startedAt", startedAt.toString()
        ));
        
        // Initialize tracking collections (thread-safe)
        this.nodeResults = new ConcurrentHashMap<>();
        this.visitedNodes = ConcurrentHashMap.newKeySet();
        this.completedNodes = ConcurrentHashMap.newKeySet();
        this.failedNodes = ConcurrentHashMap.newKeySet();
        this.skippedNodes = ConcurrentHashMap.newKeySet();
        this.nodeAttempts = new ConcurrentHashMap<>();
    }
    
    // ===== Status Management =====
    
    public void markAsQueued() {
        this.status = ExecutionStatus.QUEUED;
    }
    
    public void markAsRunning() {
        this.status = ExecutionStatus.RUNNING;
    }
    
    public void markAsWaitingTool() {
        this.status = ExecutionStatus.WAITING_TOOL;
    }
    
    public void markAsWaitingChildJob() {
        this.status = ExecutionStatus.WAITING_CHILD_JOB;
    }
    
    public void markAsCompleted() {
        this.status = ExecutionStatus.COMPLETED;
        this.completedAt = OffsetDateTime.now();
    }
    
    public void markAsFailed(String error) {
        this.status = ExecutionStatus.FAILED;
        this.error = error;
        this.completedAt = OffsetDateTime.now();
    }
    
    public void markAsCancelled() {
        this.status = ExecutionStatus.CANCELLED;
        this.completedAt = OffsetDateTime.now();
    }
    
    public void markAsTimedOut() {
        this.status = ExecutionStatus.TIMED_OUT;
        this.error = "Execution exceeded timeout";
        this.completedAt = OffsetDateTime.now();
    }
    
    // ===== Node Tracking =====
    
    public void markNodeVisited(String nodeId) {
        visitedNodes.add(nodeId);
    }
    
    public void markNodeCompleted(String nodeId, NodeResult result) {
        completedNodes.add(nodeId);
        failedNodes.remove(nodeId);
        skippedNodes.remove(nodeId);
        nodeResults.put(nodeId, result);
        
        // Update context.nodeResults[nodeId]
        updateContextWithNodeResult(nodeId, result);
    }
    
    public void markNodeFailed(String nodeId, NodeResult result) {
        failedNodes.add(nodeId);
        completedNodes.remove(nodeId);
        skippedNodes.remove(nodeId);
        nodeResults.put(nodeId, result);
        
        // Update context.nodeResults[nodeId]
        updateContextWithNodeResult(nodeId, result);
    }
    
    public void markNodeSkipped(String nodeId) {
        skippedNodes.add(nodeId);
        nodeResults.put(nodeId, NodeResult.skipped(nodeId));
    }
    
    private void updateContextWithNodeResult(String nodeId, NodeResult result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeResultsMap = (Map<String, Object>) 
            context.computeIfAbsent("nodeResults", k -> new ConcurrentHashMap<>());
        
        // Store result data in context
        if (result.data() != null) {
            nodeResultsMap.put(nodeId, result.data());
        }
    }
    
    public void incrementNodeAttempt(String nodeId) {
        nodeAttempts.merge(nodeId, 1, Integer::sum);
    }
    
    public int getNodeAttemptCount(String nodeId) {
        return nodeAttempts.getOrDefault(nodeId, 0);
    }
    
    // ===== Context Management =====
    
    public void updateContext(String key, Object value) {
        // Don't allow overwriting read-only keys
        if (key.equals("input") || key.equals("execution")) {
            throw new IllegalArgumentException("Cannot overwrite read-only context key: " + key);
        }
        context.put(key, value);
    }
    
    public Object getContextValue(String key) {
        return context.get(key);
    }
    
    public Map<String, Object> getContext() {
        return Collections.unmodifiableMap(context);
    }
    
    public Map<String, Object> getInput() {
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) context.get("input");
        return input != null ? input : Map.of();
    }
    
    // ===== Query Methods =====
    
    public boolean isNodeVisited(String nodeId) {
        return visitedNodes.contains(nodeId);
    }
    
    public boolean isNodeCompleted(String nodeId) {
        return completedNodes.contains(nodeId);
    }
    
    public boolean isNodeFailed(String nodeId) {
        return failedNodes.contains(nodeId);
    }
    
    public boolean isNodeSkipped(String nodeId) {
        return skippedNodes.contains(nodeId);
    }
    
    public Optional<NodeResult> getNodeResult(String nodeId) {
        return Optional.ofNullable(nodeResults.get(nodeId));
    }
    
    public boolean areAllNodesCompleted(Collection<String> nodeIds) {
        return nodeIds.stream().allMatch(completedNodes::contains);
    }
    
    public boolean hasAnyNodeFailed(Collection<String> nodeIds) {
        return nodeIds.stream().anyMatch(failedNodes::contains);
    }
    
    // ===== Getters =====
    
    public UUID getExecutionId() {
        return executionId;
    }
    
    public UUID getTenantId() {
        return tenantId;
    }
    
    public UUID getAgentId() {
        return agentId;
    }
    
    public UUID getAgentVersionId() {
        return agentVersionId;
    }
    
    public ExecutionStatus getStatus() {
        return status;
    }
    
    public OffsetDateTime getStartedAt() {
        return startedAt;
    }
    
    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }
    
    public String getError() {
        return error;
    }
    
    public Set<String> getVisitedNodes() {
        return Collections.unmodifiableSet(visitedNodes);
    }
    
    public Set<String> getCompletedNodes() {
        return Collections.unmodifiableSet(completedNodes);
    }
    
    public Set<String> getFailedNodes() {
        return Collections.unmodifiableSet(failedNodes);
    }
    
    public Set<String> getSkippedNodes() {
        return Collections.unmodifiableSet(skippedNodes);
    }
    
    public Map<String, NodeResult> getNodeResults() {
        return Collections.unmodifiableMap(nodeResults);
    }
    
    public boolean isTerminal() {
        return status.isTerminal();
    }
    
    public boolean isActive() {
        return status.isActive();
    }
}

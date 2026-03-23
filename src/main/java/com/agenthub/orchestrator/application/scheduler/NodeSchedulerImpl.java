package com.agenthub.orchestrator.application.scheduler;

import com.agenthub.orchestrator.application.executor.impl.HttpNodeExecutor;
import com.agenthub.orchestrator.domain.execution.model.ExecutionState;
import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of NodeScheduler
 * 
 * Uses topological ordering and dependency tracking to determine ready nodes.
 * Implements priority-based scheduling (LLM nodes, shortest path).
 * 
 * Based on specification: PIPELINE_DAG.md - Node Scheduling Algorithm
 * 
 * @since 1.0.0
 */
@Service
public class NodeSchedulerImpl implements NodeScheduler {

    private static final Logger log = LoggerFactory.getLogger(NodeSchedulerImpl.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    
    @Override
    public List<String> getReadyNodes(PipelineDefinition pipeline, ExecutionState state) {
        log.debug("Finding ready nodes: executionId={}, pipelineId={}", 
            state.getExecutionId(), pipeline.id());
        
        Set<String> readyNodes = new HashSet<>();
        
        // If no nodes visited yet, start with entry node
        if (state.getVisitedNodes().isEmpty()) {
            String entryNode = pipeline.entryNodeId();
            readyNodes.add(entryNode);
            log.debug("Starting with entry node: {}", entryNode);
            return List.of(entryNode);
        }
        
        // Find all nodes whose predecessors are satisfied
        for (PipelineNode node : pipeline.nodes()) {
            String nodeId = node.id();
            
            // Skip if already visited (unless eligible for retry)
            if (state.isNodeVisited(nodeId) && !canRetry(nodeId, state, 3)) {
                continue;
            }
            
            // Skip if already completed or skipped
            if (state.isNodeCompleted(nodeId) || state.isNodeSkipped(nodeId)) {
                continue;
            }
            
            // Check if all predecessors are satisfied
            if (arePredecessorsSatisfied(nodeId, pipeline, state)) {
                readyNodes.add(nodeId);
            }
        }
        
        // Apply priority ordering
        List<String> orderedNodes = applyPriorityOrdering(readyNodes, pipeline, state);
        
        log.debug("Ready nodes found: count={}, nodes={}", orderedNodes.size(), orderedNodes);
        
        return orderedNodes;
    }
    
    @Override
    public boolean isExecutionComplete(PipelineDefinition pipeline, ExecutionState state) {
        log.trace("Checking if execution complete: executionId={}", state.getExecutionId());
        
        // Check if any OUTPUT node is completed
        boolean hasCompletedOutput = pipeline.nodes().stream()
            .filter(node -> node.type() == NodeType.OUTPUT)
            .anyMatch(node -> state.isNodeCompleted(node.id()));
        
        if (hasCompletedOutput) {
            log.debug("Execution complete: OUTPUT node reached");
            return true;
        }
        
        // Check if all reachable nodes are in terminal state
        Set<String> reachableNodes = getReachableNodes(pipeline, state);
        boolean allTerminal = reachableNodes.stream()
            .allMatch(nodeId -> 
                state.isNodeCompleted(nodeId) || 
                isFailedWithoutRetry(nodeId, state) ||
                state.isNodeSkipped(nodeId)
            );
        
        if (allTerminal && !reachableNodes.isEmpty()) {
            log.debug("Execution complete: All reachable nodes are terminal");
            return true;
        }
        
        // Check if there are no more ready nodes and no running nodes
        List<String> readyNodes = getReadyNodes(pipeline, state);
        if (readyNodes.isEmpty() && !hasRunningNodes(state)) {
            log.debug("Execution complete: No ready nodes and no running nodes");
            return true;
        }
        
        return false;
    }
    
    @Override
    public boolean canRetry(String nodeId, ExecutionState state, int maxRetries) {
        // Node must have failed
        if (!state.isNodeFailed(nodeId)) {
            return false;
        }

        if (state.getNodeResult(nodeId)
            .map(result -> result.error() != null && result.error().startsWith(HttpNodeExecutor.NON_RETRYABLE_ERROR_PREFIX))
            .orElse(false)) {
            return false;
        }
        
        // Check retry count
        int attempts = state.getNodeAttemptCount(nodeId);
        return attempts < maxRetries;
    }
    
    @Override
    public int getProgress(PipelineDefinition pipeline, ExecutionState state) {
        int totalNodes = pipeline.getNodeCount();
        if (totalNodes == 0) {
            return 100;
        }
        
        int completedCount = state.getCompletedNodes().size();
        int skippedCount = state.getSkippedNodes().size();
        int terminalCount = completedCount + skippedCount;
        
        return (terminalCount * 100) / totalNodes;
    }
    
    // ===== Private Helper Methods =====
    
    /**
     * Check if all predecessors are satisfied (completed or skipped)
     */
    private boolean arePredecessorsSatisfied(
        String nodeId,
        PipelineDefinition pipeline,
        ExecutionState state
    ) {
        List<String> predecessors = pipeline.getPredecessors(nodeId);
        
        // No predecessors means node is ready (could be entry node)
        if (predecessors.isEmpty()) {
            return nodeId.equals(pipeline.entryNodeId());
        }
        
        // All predecessors must be completed or skipped
        return predecessors.stream()
            .allMatch(predId -> 
                state.isNodeCompleted(predId) || state.isNodeSkipped(predId)
            );
    }
    
    /**
     * Get all reachable nodes from current state
     * (nodes that can still be reached given completed/failed nodes)
     */
    private Set<String> getReachableNodes(PipelineDefinition pipeline, ExecutionState state) {
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        
        // Start from entry node
        queue.add(pipeline.entryNodeId());
        reachable.add(pipeline.entryNodeId());
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            
            // Don't traverse through failed nodes
            if (state.isNodeFailed(current)) {
                continue;
            }
            
            // Add all successors
            for (String successor : pipeline.getSuccessors(current)) {
                if (reachable.add(successor)) {
                    queue.add(successor);
                }
            }
        }
        
        return reachable;
    }
    
    /**
     * Check if there are any nodes currently running
     * (visited but not completed/failed/skipped)
     */
    private boolean hasRunningNodes(ExecutionState state) {
        Set<String> visited = new HashSet<>(state.getVisitedNodes());
        visited.removeAll(state.getCompletedNodes());
        visited.removeAll(state.getFailedNodes());
        visited.removeAll(state.getSkippedNodes());
        
        return !visited.isEmpty();
    }

    private boolean isFailedWithoutRetry(String nodeId, ExecutionState state) {
        return state.isNodeFailed(nodeId) && !canRetry(nodeId, state, DEFAULT_MAX_RETRIES);
    }
    
    /**
     * Apply priority ordering to ready nodes
     * 
     * Priority rules (from spec):
     * 1. LLM nodes first (critical path)
     * 2. Nodes with shortest path to OUTPUT
     * 3. Lexicographic order (stable sort)
     */
    private List<String> applyPriorityOrdering(
        Set<String> readyNodes,
        PipelineDefinition pipeline,
        ExecutionState state
    ) {
        return readyNodes.stream()
            .sorted((nodeId1, nodeId2) -> {
                PipelineNode node1 = pipeline.getNodeById(nodeId1).orElseThrow();
                PipelineNode node2 = pipeline.getNodeById(nodeId2).orElseThrow();
                
                // 1. LLM nodes first
                boolean isLlm1 = node1.type().isAI();
                boolean isLlm2 = node2.type().isAI();
                
                if (isLlm1 && !isLlm2) return -1;
                if (!isLlm1 && isLlm2) return 1;
                
                // 2. Shortest path to OUTPUT (simplified - BFS distance)
                int distance1 = getDistanceToOutput(nodeId1, pipeline);
                int distance2 = getDistanceToOutput(nodeId2, pipeline);
                
                if (distance1 != distance2) {
                    return Integer.compare(distance1, distance2);
                }
                
                // 3. Lexicographic order (stable)
                return nodeId1.compareTo(nodeId2);
            })
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate distance from node to nearest OUTPUT node (BFS)
     * Returns Integer.MAX_VALUE if no path exists
     */
    private int getDistanceToOutput(String startNodeId, PipelineDefinition pipeline) {
        Queue<String> queue = new LinkedList<>();
        Map<String, Integer> distances = new HashMap<>();
        
        queue.add(startNodeId);
        distances.put(startNodeId, 0);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDistance = distances.get(current);
            
            // Check if current is OUTPUT
            PipelineNode node = pipeline.getNodeById(current).orElseThrow();
            if (node.type() == NodeType.OUTPUT) {
                return currentDistance;
            }
            
            // Visit successors
            for (String successor : pipeline.getSuccessors(current)) {
                if (!distances.containsKey(successor)) {
                    distances.put(successor, currentDistance + 1);
                    queue.add(successor);
                }
            }
        }
        
        return Integer.MAX_VALUE; // No path to OUTPUT
    }
}

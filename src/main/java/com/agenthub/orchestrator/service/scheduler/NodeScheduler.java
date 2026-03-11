package com.agenthub.orchestrator.service.scheduler;

import com.agenthub.orchestrator.domain.execution.ExecutionState;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;

import java.util.List;

/**
 * Node Scheduler - Determines which nodes are ready to execute
 * 
 * Based on specification: PIPELINE_DAG.md, ARCHITECTURE.md
 * 
 * Algorithm:
 * 1. Find all nodes whose predecessors are completed
 * 2. Filter out already visited/completed/failed nodes
 * 3. Apply priority (shortest path, LLM nodes first)
 * 4. Return ready nodes for execution
 * 
 * Supports:
 * - Parallel execution
 * - Conditional branches
 * - FOREACH iterations
 * - Retry logic
 * 
 * @since 1.0.0
 */
public interface NodeScheduler {
    
    /**
     * Get nodes that are ready to execute
     * 
     * A node is ready when:
     * - All predecessor nodes are completed (or skipped)
     * - Node has not been visited yet (unless retry)
     * - Node is not in failed state
     * 
     * @param pipeline Pipeline definition (DAG structure)
     * @param state Current execution state
     * @return List of node IDs ready to execute (ordered by priority)
     */
    List<String> getReadyNodes(PipelineDefinition pipeline, ExecutionState state);
    
    /**
     * Check if execution is complete
     * 
     * Execution is complete when:
     * - At least one OUTPUT node is completed, OR
     * - All reachable nodes are completed/failed/skipped, OR
     * - A terminal failure occurred (based on failure policy)
     * 
     * @param pipeline Pipeline definition
     * @param state Current execution state
     * @return true if execution is complete
     */
    boolean isExecutionComplete(PipelineDefinition pipeline, ExecutionState state);
    
    /**
     * Check if node can be retried
     * 
     * @param nodeId Node ID
     * @param state Current execution state
     * @param maxRetries Maximum retry attempts
     * @return true if node can be retried
     */
    boolean canRetry(String nodeId, ExecutionState state, int maxRetries);
    
    /**
     * Get execution progress percentage
     * 
     * @param pipeline Pipeline definition
     * @param state Current execution state
     * @return Progress percentage (0-100)
     */
    int getProgress(PipelineDefinition pipeline, ExecutionState state);
}

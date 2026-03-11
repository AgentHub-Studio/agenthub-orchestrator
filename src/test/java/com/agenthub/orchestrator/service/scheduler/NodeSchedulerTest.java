package com.agenthub.orchestrator.service.scheduler;

import com.agenthub.orchestrator.domain.execution.ExecutionState;
import com.agenthub.orchestrator.domain.execution.NodeResult;
import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineEdge;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NodeScheduler
 * 
 * @since 1.0.0
 */
class NodeSchedulerTest {
    
    private NodeScheduler scheduler;
    
    private UUID tenantId;
    private UUID agentId;
    private UUID agentVersionId;
    
    @BeforeEach
    void setUp() {
        scheduler = new NodeSchedulerImpl();
        
        tenantId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        agentVersionId = UUID.randomUUID();
    }
    
    @Test
    void testGetReadyNodesInitialState() {
        // Given: Linear pipeline (input -> llm -> output)
        PipelineDefinition pipeline = createLinearPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // When: Get ready nodes (no nodes visited yet)
        List<String> readyNodes = scheduler.getReadyNodes(pipeline, state);
        
        // Then: Only entry node should be ready
        assertEquals(1, readyNodes.size());
        assertEquals("input", readyNodes.get(0));
    }
    
    @Test
    void testGetReadyNodesAfterFirstNode() {
        // Given: Linear pipeline with first node completed
        PipelineDefinition pipeline = createLinearPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // Complete input node
        state.markNodeVisited("input");
        state.markNodeCompleted("input", createSuccessResult("input"));
        
        // When: Get ready nodes
        List<String> readyNodes = scheduler.getReadyNodes(pipeline, state);
        
        // Then: Next node (llm) should be ready
        assertEquals(1, readyNodes.size());
        assertEquals("llm", readyNodes.get(0));
    }
    
    @Test
    void testGetReadyNodesParallelExecution() {
        // Given: Parallel pipeline (input -> [http, sql] -> merge -> output)
        PipelineDefinition pipeline = createParallelPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // Complete input node
        state.markNodeVisited("input");
        state.markNodeCompleted("input", createSuccessResult("input"));
        
        // When: Get ready nodes
        List<String> readyNodes = scheduler.getReadyNodes(pipeline, state);
        
        // Then: Both parallel branches should be ready
        assertEquals(2, readyNodes.size());
        assertTrue(readyNodes.contains("http"));
        assertTrue(readyNodes.contains("sql"));
    }
    
    @Test
    void testGetReadyNodesWaitingForMerge() {
        // Given: Parallel pipeline with one branch completed
        PipelineDefinition pipeline = createParallelPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // Complete input and http
        state.markNodeVisited("input");
        state.markNodeCompleted("input", createSuccessResult("input"));
        state.markNodeVisited("http");
        state.markNodeCompleted("http", createSuccessResult("http"));
        
        // When: Get ready nodes
        List<String> readyNodes = scheduler.getReadyNodes(pipeline, state);
        
        // Then: Only sql should be ready (merge waits for both)
        assertEquals(1, readyNodes.size());
        assertEquals("sql", readyNodes.get(0));
    }
    
    @Test
    void testGetReadyNodesMergeReady() {
        // Given: Parallel pipeline with both branches completed
        PipelineDefinition pipeline = createParallelPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // Complete input, http, and sql
        state.markNodeVisited("input");
        state.markNodeCompleted("input", createSuccessResult("input"));
        state.markNodeVisited("http");
        state.markNodeCompleted("http", createSuccessResult("http"));
        state.markNodeVisited("sql");
        state.markNodeCompleted("sql", createSuccessResult("sql"));
        
        // When: Get ready nodes
        List<String> readyNodes = scheduler.getReadyNodes(pipeline, state);
        
        // Then: Merge should be ready
        assertEquals(1, readyNodes.size());
        assertEquals("merge", readyNodes.get(0));
    }
    
    @Test
    void testIsExecutionCompleteOutputReached() {
        // Given: Linear pipeline with all nodes completed
        PipelineDefinition pipeline = createLinearPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // Complete all nodes
        state.markNodeCompleted("input", createSuccessResult("input"));
        state.markNodeCompleted("llm", createSuccessResult("llm"));
        state.markNodeCompleted("output", createSuccessResult("output"));
        
        // When: Check if complete
        boolean isComplete = scheduler.isExecutionComplete(pipeline, state);
        
        // Then: Should be complete
        assertTrue(isComplete);
    }
    
    @Test
    void testIsExecutionCompleteNotDone() {
        // Given: Linear pipeline with only first node completed
        PipelineDefinition pipeline = createLinearPipeline();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        state.markNodeCompleted("input", createSuccessResult("input"));
        
        // When: Check if complete
        boolean isComplete = scheduler.isExecutionComplete(pipeline, state);
        
        // Then: Should not be complete
        assertFalse(isComplete);
    }
    
    @Test
    void testCanRetryFailedNode() {
        // Given: Failed node with attempts < maxRetries
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        state.markNodeFailed("node1", createFailureResult("node1", "Error"));
        state.incrementNodeAttempt("node1");
        
        // When: Check if can retry (maxRetries = 3)
        boolean canRetry = scheduler.canRetry("node1", state, 3);
        
        // Then: Should be able to retry
        assertTrue(canRetry);
    }
    
    @Test
    void testCannotRetryMaxAttemptsReached() {
        // Given: Failed node with attempts = maxRetries
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        state.markNodeFailed("node1", createFailureResult("node1", "Error"));
        state.incrementNodeAttempt("node1");
        state.incrementNodeAttempt("node1");
        state.incrementNodeAttempt("node1");
        
        // When: Check if can retry (maxRetries = 3)
        boolean canRetry = scheduler.canRetry("node1", state, 3);
        
        // Then: Should NOT be able to retry
        assertFalse(canRetry);
    }
    
    @Test
    void testGetProgress() {
        // Given: Pipeline with 3 nodes, 2 completed
        PipelineDefinition pipeline = createLinearPipeline(); // 3 nodes
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        state.markNodeCompleted("input", createSuccessResult("input"));
        state.markNodeCompleted("llm", createSuccessResult("llm"));
        
        // When: Get progress
        int progress = scheduler.getProgress(pipeline, state);
        
        // Then: Should be 66% (2/3)
        assertEquals(66, progress);
    }
    
    @Test
    void testGetProgressWithSkippedNodes() {
        // Given: Pipeline with completed and skipped nodes
        PipelineDefinition pipeline = createLinearPipeline(); // 3 nodes
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        state.markNodeCompleted("input", createSuccessResult("input"));
        state.markNodeSkipped("llm");
        state.markNodeCompleted("output", createSuccessResult("output"));
        
        // When: Get progress
        int progress = scheduler.getProgress(pipeline, state);
        
        // Then: Should be 100% (3/3 terminal)
        assertEquals(100, progress);
    }
    
    @Test
    void testPriorityOrderingLlmFirst() {
        // Given: Pipeline with LLM and non-LLM nodes ready
        PipelineDefinition pipeline = createPipelineWithMixedTypes();
        ExecutionState state = new ExecutionState(UUID.randomUUID(), tenantId, agentId, agentVersionId, Map.of());
        
        // Complete input so both transform and llm are ready
        state.markNodeVisited("input");
        state.markNodeCompleted("input", createSuccessResult("input"));
        
        // When: Get ready nodes
        List<String> readyNodes = scheduler.getReadyNodes(pipeline, state);
        
        // Then: LLM node should come first (priority rule 1)
        assertTrue(readyNodes.indexOf("llm") < readyNodes.indexOf("transform"));
    }
    
    // ===== Helper Methods =====
    
    private PipelineDefinition createLinearPipeline() {
        UUID id = UUID.randomUUID();
        
        PipelineNode input = new PipelineNode(
            "input", NodeType.INPUT, "Input", Map.of(), new PipelineNode.Position(100, 200)
        );
        PipelineNode llm = new PipelineNode(
            "llm", NodeType.LLM, "LLM", Map.of(), new PipelineNode.Position(300, 200)
        );
        PipelineNode output = new PipelineNode(
            "output", NodeType.OUTPUT, "Output", Map.of(), new PipelineNode.Position(500, 200)
        );
        
        return new PipelineDefinition(
            id, agentId, "Linear Pipeline", 1, "input",
            List.of(input, llm, output),
            List.of(
                new PipelineEdge("e1", "input", "llm", "output", "input", null),
                new PipelineEdge("e2", "llm", "output", "output", "input", null)
            )
        );
    }
    
    private PipelineDefinition createParallelPipeline() {
        UUID id = UUID.randomUUID();
        
        PipelineNode input = new PipelineNode(
            "input", NodeType.INPUT, "Input", Map.of(), new PipelineNode.Position(100, 200)
        );
        PipelineNode http = new PipelineNode(
            "http", NodeType.HTTP, "HTTP", Map.of(), new PipelineNode.Position(300, 100)
        );
        PipelineNode sql = new PipelineNode(
            "sql", NodeType.SQL, "SQL", Map.of(), new PipelineNode.Position(300, 300)
        );
        PipelineNode merge = new PipelineNode(
            "merge", NodeType.MERGE, "Merge", Map.of(), new PipelineNode.Position(500, 200)
        );
        PipelineNode output = new PipelineNode(
            "output", NodeType.OUTPUT, "Output", Map.of(), new PipelineNode.Position(700, 200)
        );
        
        return new PipelineDefinition(
            id, agentId, "Parallel Pipeline", 1, "input",
            List.of(input, http, sql, merge, output),
            List.of(
                new PipelineEdge("e1", "input", "http", "output", "input", null),
                new PipelineEdge("e2", "input", "sql", "output", "input", null),
                new PipelineEdge("e3", "http", "merge", "output", "input1", null),
                new PipelineEdge("e4", "sql", "merge", "output", "input2", null),
                new PipelineEdge("e5", "merge", "output", "output", "input", null)
            )
        );
    }
    
    private PipelineDefinition createPipelineWithMixedTypes() {
        UUID id = UUID.randomUUID();
        
        PipelineNode input = new PipelineNode(
            "input", NodeType.INPUT, "Input", Map.of(), new PipelineNode.Position(100, 200)
        );
        PipelineNode transform = new PipelineNode(
            "transform", NodeType.TRANSFORM, "Transform", Map.of(), new PipelineNode.Position(300, 100)
        );
        PipelineNode llm = new PipelineNode(
            "llm", NodeType.LLM, "LLM", Map.of(), new PipelineNode.Position(300, 300)
        );
        PipelineNode output = new PipelineNode(
            "output", NodeType.OUTPUT, "Output", Map.of(), new PipelineNode.Position(500, 200)
        );
        
        return new PipelineDefinition(
            id, agentId, "Mixed Types Pipeline", 1, "input",
            List.of(input, transform, llm, output),
            List.of(
                new PipelineEdge("e1", "input", "transform", "output", "input", null),
                new PipelineEdge("e2", "input", "llm", "output", "input", null),
                new PipelineEdge("e3", "transform", "output", "output", "input", null),
                new PipelineEdge("e4", "llm", "output", "output", "input", null)
            )
        );
    }
    
    private NodeResult createSuccessResult(String nodeId) {
        return NodeResult.success(
            nodeId,
            Map.of("result", "success"),
            OffsetDateTime.now().minusSeconds(1),
            OffsetDateTime.now(),
            1
        );
    }
    
    private NodeResult createFailureResult(String nodeId, String error) {
        return NodeResult.failure(
            nodeId,
            error,
            OffsetDateTime.now().minusSeconds(1),
            OffsetDateTime.now(),
            1
        );
    }
}

package com.agenthub.orchestrator.service.pipeline;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineEdge;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PipelineDefinitionService
 * 
 * @since 1.0.0
 */
class PipelineDefinitionServiceTest {
    
    private PipelineDefinitionService service;
    
    @BeforeEach
    void setUp() {
        service = new PipelineDefinitionServiceImpl(null, null);
    }
    
    @Test
    void testValidateSimpleLinearPipeline() {
        // Given: Simple linear pipeline (INPUT -> LLM -> OUTPUT)
        PipelineDefinition pipeline = createSimpleLinearPipeline();
        
        // When: Validate
        ValidationResult result = service.validatePipeline(pipeline);
        
        // Then: Should be valid
        assertTrue(result.isValid());
        assertFalse(result.hasErrors());
    }
    
    @Test
    void testValidatePipelineWithCycle() {
        // Given: Pipeline with cycle (A -> B -> C -> A)
        PipelineDefinition pipeline = createPipelineWithCycle();
        
        // When: Validate
        ValidationResult result = service.validatePipeline(pipeline);
        
        // Then: Should be invalid
        assertFalse(result.isValid());
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().get(0).contains("cycle"));
    }
    
    @Test
    void testValidatePipelineWithOrphanNodes() {
        // Given: Pipeline with orphan nodes (disconnected)
        PipelineDefinition pipeline = createPipelineWithOrphans();
        
        // When: Validate
        ValidationResult result = service.validatePipeline(pipeline);
        
        // Then: Should be valid but with warnings
        assertTrue(result.isValid());
        assertTrue(result.hasWarnings());
        assertTrue(result.getWarnings().get(0).contains("Unreachable"));
    }
    
    @Test
    void testValidateParallelBranches() {
        // Given: Pipeline with parallel branches
        PipelineDefinition pipeline = createParallelPipeline();
        
        // When: Validate
        ValidationResult result = service.validatePipeline(pipeline);
        
        // Then: Should be valid
        assertTrue(result.isValid());
        assertFalse(result.hasErrors());
    }
    
    // ===== Helper Methods to Create Test Pipelines =====
    
    private PipelineDefinition createSimpleLinearPipeline() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        
        PipelineNode input = new PipelineNode(
            "input",
            NodeType.INPUT,
            "Input",
            Map.of(),
            new PipelineNode.Position(100, 200)
        );
        
        PipelineNode llm = new PipelineNode(
            "llm",
            NodeType.LLM,
            "LLM Call",
            Map.of("model", "gpt-4"),
            new PipelineNode.Position(300, 200)
        );
        
        PipelineNode output = new PipelineNode(
            "output",
            NodeType.OUTPUT,
            "Output",
            Map.of(),
            new PipelineNode.Position(500, 200)
        );
        
        PipelineEdge edge1 = new PipelineEdge(
            "e1", "input", "llm", "output", "input", null
        );
        
        PipelineEdge edge2 = new PipelineEdge(
            "e2", "llm", "output", "output", "input", null
        );
        
        return new PipelineDefinition(
            id,
            agentId,
            "Simple Linear Pipeline",
            1,
            "input",
            List.of(input, llm, output),
            List.of(edge1, edge2)
        );
    }
    
    private PipelineDefinition createPipelineWithCycle() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        
        PipelineNode nodeA = new PipelineNode(
            "a", NodeType.INPUT, "Node A", Map.of(), new PipelineNode.Position(100, 200)
        );
        
        PipelineNode nodeB = new PipelineNode(
            "b", NodeType.TRANSFORM, "Node B", Map.of(), new PipelineNode.Position(300, 200)
        );
        
        PipelineNode nodeC = new PipelineNode(
            "c", NodeType.OUTPUT, "Node C", Map.of(), new PipelineNode.Position(500, 200)
        );
        
        // Create cycle: A -> B -> C -> A
        PipelineEdge edge1 = new PipelineEdge("e1", "a", "b", "output", "input", null);
        PipelineEdge edge2 = new PipelineEdge("e2", "b", "c", "output", "input", null);
        PipelineEdge edge3 = new PipelineEdge("e3", "c", "a", "output", "input", null); // Creates cycle!
        
        return new PipelineDefinition(
            id, agentId, "Pipeline with Cycle", 1, "a",
            List.of(nodeA, nodeB, nodeC),
            List.of(edge1, edge2, edge3)
        );
    }
    
    private PipelineDefinition createPipelineWithOrphans() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        
        // Main flow: input -> output
        PipelineNode input = new PipelineNode(
            "input", NodeType.INPUT, "Input", Map.of(), new PipelineNode.Position(100, 200)
        );
        
        PipelineNode output = new PipelineNode(
            "output", NodeType.OUTPUT, "Output", Map.of(), new PipelineNode.Position(300, 200)
        );
        
        // Orphan node (not connected)
        PipelineNode orphan = new PipelineNode(
            "orphan", NodeType.LLM, "Orphan", Map.of(), new PipelineNode.Position(500, 200)
        );
        
        PipelineEdge edge1 = new PipelineEdge("e1", "input", "output", "output", "input", null);
        
        return new PipelineDefinition(
            id, agentId, "Pipeline with Orphans", 1, "input",
            List.of(input, output, orphan),
            List.of(edge1)
        );
    }
    
    private PipelineDefinition createParallelPipeline() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        
        PipelineNode input = new PipelineNode(
            "input", NodeType.INPUT, "Input", Map.of(), new PipelineNode.Position(100, 200)
        );
        
        PipelineNode http = new PipelineNode(
            "http", NodeType.HTTP, "HTTP Call", Map.of(), new PipelineNode.Position(300, 100)
        );
        
        PipelineNode sql = new PipelineNode(
            "sql", NodeType.SQL, "SQL Query", Map.of(), new PipelineNode.Position(300, 300)
        );
        
        PipelineNode merge = new PipelineNode(
            "merge", NodeType.MERGE, "Merge Results", Map.of(), new PipelineNode.Position(500, 200)
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
}

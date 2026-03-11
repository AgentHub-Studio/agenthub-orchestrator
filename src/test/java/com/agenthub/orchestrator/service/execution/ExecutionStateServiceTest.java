package com.agenthub.orchestrator.service.execution;

import com.agenthub.orchestrator.domain.execution.ExecutionState;
import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import com.agenthub.orchestrator.domain.execution.NodeResult;
import com.agenthub.orchestrator.exception.ExecutionNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExecutionStateService
 * 
 * @since 1.0.0
 */
class ExecutionStateServiceTest {
    
    private ExecutionStateService service;
    private ExecutionStateServiceImpl serviceImpl;
    
    private UUID tenantId;
    private UUID agentId;
    private UUID agentVersionId;
    
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        serviceImpl = new ExecutionStateServiceImpl(objectMapper);
        service = serviceImpl;
        
        tenantId = UUID.randomUUID();
        agentId = UUID.randomUUID();
        agentVersionId = UUID.randomUUID();
        
        // Clear any existing state
        serviceImpl.clearAll();
    }
    
    @Test
    void testCreateExecution() {
        // Given: Input data
        Map<String, Object> input = Map.of("question", "What is AI?");
        
        // When: Create execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, input);
        
        // Then: Should be created with correct values
        assertNotNull(state);
        assertNotNull(state.getExecutionId());
        assertEquals(tenantId, state.getTenantId());
        assertEquals(agentId, state.getAgentId());
        assertEquals(agentVersionId, state.getAgentVersionId());
        assertEquals(ExecutionStatus.PENDING, state.getStatus());
        assertEquals(input, state.getInput());
        assertNotNull(state.getStartedAt());
    }
    
    @Test
    void testLoadExecution() {
        // Given: Created execution
        ExecutionState created = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = created.getExecutionId();
        
        // When: Load execution
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        
        // Then: Should be same execution
        assertNotNull(loaded);
        assertEquals(executionId, loaded.getExecutionId());
        assertEquals(tenantId, loaded.getTenantId());
    }
    
    @Test
    void testLoadExecutionNotFound() {
        // Given: Non-existent execution ID
        UUID nonExistentId = UUID.randomUUID();
        
        // When/Then: Should throw exception
        assertThrows(ExecutionNotFoundException.class, () -> {
            service.loadExecution(nonExistentId, tenantId);
        });
    }
    
    @Test
    void testLoadExecutionWrongTenant() {
        // Given: Execution for tenant A
        ExecutionState created = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = created.getExecutionId();
        UUID wrongTenantId = UUID.randomUUID();
        
        // When/Then: Loading with wrong tenant should fail
        assertThrows(ExecutionNotFoundException.class, () -> {
            service.loadExecution(executionId, wrongTenantId);
        });
    }
    
    @Test
    void testUpdateContext() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Update context
        service.updateContext(executionId, "customKey", "customValue");
        
        // Then: Context should be updated
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertEquals("customValue", loaded.getContextValue("customKey"));
    }
    
    @Test
    void testMarkNodeVisited() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Mark node visited
        service.markNodeVisited(executionId, "node1");
        
        // Then: Node should be marked as visited
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertTrue(loaded.isNodeVisited("node1"));
        assertFalse(loaded.isNodeCompleted("node1"));
    }
    
    @Test
    void testMarkNodeCompleted() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Mark node completed
        OffsetDateTime startedAt = OffsetDateTime.now().minusSeconds(5);
        OffsetDateTime completedAt = OffsetDateTime.now();
        NodeResult result = NodeResult.success("node1", Map.of("output", "result"), startedAt, completedAt, 1);
        
        service.markNodeCompleted(executionId, "node1", result);
        
        // Then: Node should be marked as completed
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertTrue(loaded.isNodeCompleted("node1"));
        assertFalse(loaded.isNodeFailed("node1"));
        
        // Should have result in context
        NodeResult savedResult = loaded.getNodeResult("node1").orElse(null);
        assertNotNull(savedResult);
        assertEquals("node1", savedResult.nodeId());
        assertTrue(savedResult.isSuccess());
    }
    
    @Test
    void testMarkNodeFailed() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Mark node failed
        OffsetDateTime startedAt = OffsetDateTime.now().minusSeconds(2);
        OffsetDateTime completedAt = OffsetDateTime.now();
        NodeResult result = NodeResult.failure("node1", "Connection timeout", startedAt, completedAt, 1);
        
        service.markNodeFailed(executionId, "node1", result);
        
        // Then: Node should be marked as failed
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertTrue(loaded.isNodeFailed("node1"));
        assertFalse(loaded.isNodeCompleted("node1"));
        
        // Should have error in result
        NodeResult savedResult = loaded.getNodeResult("node1").orElse(null);
        assertNotNull(savedResult);
        assertTrue(savedResult.isFailed());
        assertEquals("Connection timeout", savedResult.error());
    }
    
    @Test
    void testMarkNodeSkipped() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Mark node skipped
        service.markNodeSkipped(executionId, "node1");
        
        // Then: Node should be marked as skipped
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertTrue(loaded.isNodeSkipped("node1"));
        assertFalse(loaded.isNodeCompleted("node1"));
        assertFalse(loaded.isNodeFailed("node1"));
    }
    
    @Test
    void testIncrementNodeAttempt() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Increment attempt multiple times
        int attempt1 = service.incrementNodeAttempt(executionId, "node1");
        int attempt2 = service.incrementNodeAttempt(executionId, "node1");
        int attempt3 = service.incrementNodeAttempt(executionId, "node1");
        
        // Then: Should increment correctly
        assertEquals(1, attempt1);
        assertEquals(2, attempt2);
        assertEquals(3, attempt3);
        
        // Verify state
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertEquals(3, loaded.getNodeAttemptCount("node1"));
    }
    
    @Test
    void testDeleteExecution() {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // Verify it exists
        assertNotNull(service.loadExecution(executionId, tenantId));
        
        // When: Delete execution
        service.deleteExecution(executionId, tenantId);
        
        // Then: Should not exist anymore
        assertThrows(ExecutionNotFoundException.class, () -> {
            service.loadExecution(executionId, tenantId);
        });
    }
    
    @Test
    void testDeleteExecutionWrongTenant() {
        // Given: Execution for tenant A
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        UUID wrongTenantId = UUID.randomUUID();
        
        // When/Then: Deleting with wrong tenant should fail
        assertThrows(ExecutionNotFoundException.class, () -> {
            service.deleteExecution(executionId, wrongTenantId);
        });
        
        // Original execution should still exist
        assertNotNull(service.loadExecution(executionId, tenantId));
    }
    
    @Test
    void testConcurrentNodeUpdates() throws InterruptedException {
        // Given: Created execution
        ExecutionState state = service.createExecution(tenantId, agentId, agentVersionId, Map.of());
        UUID executionId = state.getExecutionId();
        
        // When: Update multiple nodes concurrently
        Thread thread1 = new Thread(() -> {
            service.markNodeVisited(executionId, "node1");
            service.markNodeCompleted(executionId, "node1", 
                NodeResult.success("node1", Map.of(), OffsetDateTime.now(), OffsetDateTime.now(), 1));
        });
        
        Thread thread2 = new Thread(() -> {
            service.markNodeVisited(executionId, "node2");
            service.markNodeCompleted(executionId, "node2", 
                NodeResult.success("node2", Map.of(), OffsetDateTime.now(), OffsetDateTime.now(), 1));
        });
        
        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
        
        // Then: Both nodes should be completed
        ExecutionState loaded = service.loadExecution(executionId, tenantId);
        assertTrue(loaded.isNodeCompleted("node1"));
        assertTrue(loaded.isNodeCompleted("node2"));
    }
}

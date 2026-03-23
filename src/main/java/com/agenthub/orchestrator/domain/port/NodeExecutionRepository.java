package com.agenthub.orchestrator.domain.port;

import com.agenthub.orchestrator.adapter.out.persistence.NodeExecutionEntity;
import com.agenthub.orchestrator.adapter.in.rest.NodeExecutionSummary;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for NodeExecution entities
 * 
 * Follows ADR-001: Use projections for list queries
 * Follows ADR-004: Method naming conventions
 * 
 * @since 1.0.0
 */
@Repository
public interface NodeExecutionRepository extends JpaRepository<NodeExecutionEntity, UUID> {

    /**
     * Delete all node executions for an execution.
     */
    @Modifying
    void deleteByExecution_Id(UUID executionId);
    
    /**
     * Find node execution summaries by execution ID (PROJECTION)
     * 
     * ADR-001: Use projections for timeline/list views
     */
    @Query("""
        SELECT n.id as id, n.execution.id as executionId, n.nodeType as nodeType, 
               n.nodeName as nodeName, n.status as status, n.startedAt as startedAt, 
               n.finishedAt as finishedAt, n.latencyMs as latencyMs, 
               n.errorMessage as errorMessage
        FROM NodeExecutionEntity n 
        WHERE n.execution.id = :executionId 
        ORDER BY n.createdAt ASC
        """)
    List<NodeExecutionSummary> findSummariesByExecutionId(@Param("executionId") UUID executionId);
    
    /**
     * Find all node executions by execution ID (full entities for processing)
     * 
     * Use this when you need the full entity with input/output JSON
     */
    @Query("SELECT n FROM NodeExecutionEntity n WHERE n.execution.id = :executionId ORDER BY n.createdAt ASC")
    List<NodeExecutionEntity> findByExecutionId(@Param("executionId") UUID executionId);
    
    /**
     * Count completed nodes for an execution
     */
    @Query("""
        SELECT COUNT(n) FROM NodeExecutionEntity n 
        WHERE n.execution.id = :executionId 
        AND n.status = 'COMPLETED'
        """)
    long countCompletedNodesByExecutionId(@Param("executionId") UUID executionId);
    
    /**
     * Count failed nodes for an execution
     */
    @Query("""
        SELECT COUNT(n) FROM NodeExecutionEntity n 
        WHERE n.execution.id = :executionId 
        AND n.status = 'FAILED'
        """)
    long countFailedNodesByExecutionId(@Param("executionId") UUID executionId);
}

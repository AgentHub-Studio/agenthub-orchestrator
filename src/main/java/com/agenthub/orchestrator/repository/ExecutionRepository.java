package com.agenthub.orchestrator.repository;

import com.agenthub.orchestrator.entity.ExecutionEntity;
import com.agenthub.orchestrator.projection.ExecutionSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Execution entities
 * 
 * Follows ADR-001: Use projections for list queries (performance)
 * Follows ADR-002: Multi-tenancy mandatory (tenant_id in WHERE)
 * Follows ADR-004: Method naming conventions
 * 
 * @since 1.0.0
 */
@Repository
public interface ExecutionRepository extends JpaRepository<ExecutionEntity, UUID> {
    
    /**
     * Find execution by ID with tenant isolation
     * 
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("SELECT e FROM ExecutionEntity e WHERE e.id = :id AND e.tenantId = :tenantId")
    Optional<ExecutionEntity> findByIdAndTenantId(
        @Param("id") UUID id, 
        @Param("tenantId") UUID tenantId
    );
    
    /**
     * Find execution summaries by tenant (PROJECTION for performance)
     * 
     * ADR-001: Use projections instead of loading full entities
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("""
        SELECT e.id as id, e.tenantId as tenantId, e.agentId as agentId, 
               e.agentVersionId as agentVersionId, e.status as status, 
               e.triggerType as triggerType, e.startedAt as startedAt, 
               e.finishedAt as finishedAt, e.createdAt as createdAt
        FROM ExecutionEntity e 
        WHERE e.tenantId = :tenantId 
        ORDER BY e.createdAt DESC
        """)
    Page<ExecutionSummary> findSummariesByTenantId(
        @Param("tenantId") UUID tenantId, 
        Pageable pageable
    );
    
    /**
     * Find execution summaries by tenant and status (PROJECTION)
     * 
     * ADR-001: Use projections
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("""
        SELECT e.id as id, e.tenantId as tenantId, e.agentId as agentId, 
               e.agentVersionId as agentVersionId, e.status as status, 
               e.triggerType as triggerType, e.startedAt as startedAt, 
               e.finishedAt as finishedAt, e.createdAt as createdAt
        FROM ExecutionEntity e 
        WHERE e.tenantId = :tenantId 
        AND e.status = :status 
        ORDER BY e.createdAt DESC
        """)
    Page<ExecutionSummary> findSummariesByTenantIdAndStatus(
        @Param("tenantId") UUID tenantId,
        @Param("status") String status,
        Pageable pageable
    );
    
    /**
     * Find execution summaries by agent (PROJECTION)
     * 
     * ADR-001: Use projections
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("""
        SELECT e.id as id, e.tenantId as tenantId, e.agentId as agentId, 
               e.agentVersionId as agentVersionId, e.status as status, 
               e.triggerType as triggerType, e.startedAt as startedAt, 
               e.finishedAt as finishedAt, e.createdAt as createdAt
        FROM ExecutionEntity e 
        WHERE e.tenantId = :tenantId 
        AND e.agentId = :agentId 
        ORDER BY e.createdAt DESC
        """)
    Page<ExecutionSummary> findSummariesByTenantIdAndAgentId(
        @Param("tenantId") UUID tenantId,
        @Param("agentId") UUID agentId,
        Pageable pageable
    );
    
    /**
     * Count running executions for a tenant
     * 
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("""
        SELECT COUNT(e) FROM ExecutionEntity e 
        WHERE e.tenantId = :tenantId 
        AND e.status IN ('PENDING', 'RUNNING')
        """)
    long countRunningExecutionsByTenantId(@Param("tenantId") UUID tenantId);
}

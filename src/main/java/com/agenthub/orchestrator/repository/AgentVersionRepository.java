package com.agenthub.orchestrator.repository;

import com.agenthub.orchestrator.entity.AgentVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AgentVersion entities
 * 
 * Follows ADR-001: Use projections for performance
 * Follows ADR-002: Multi-tenancy mandatory (tenant_id in WHERE)
 * Follows ADR-004: Method naming conventions
 * 
 * @since 1.0.0
 */
@Repository
public interface AgentVersionRepository extends JpaRepository<AgentVersionEntity, UUID> {
    
    /**
     * Find agent version by ID with tenant isolation
     * 
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("SELECT av FROM AgentVersionEntity av WHERE av.id = :id AND av.tenantId = :tenantId")
    Optional<AgentVersionEntity> findByIdAndTenantId(
        @Param("id") UUID id, 
        @Param("tenantId") UUID tenantId
    );
    
    /**
     * Find latest published version for an agent
     * 
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("""
        SELECT av FROM AgentVersionEntity av 
        WHERE av.agentId = :agentId 
        AND av.tenantId = :tenantId 
        AND av.status = 'PUBLISHED' 
        ORDER BY av.version DESC 
        LIMIT 1
        """)
    Optional<AgentVersionEntity> findLatestPublishedByAgentIdAndTenantId(
        @Param("agentId") UUID agentId,
        @Param("tenantId") UUID tenantId
    );
    
    /**
     * Find specific version for an agent
     * 
     * ADR-002: Multi-tenancy mandatory
     */
    @Query("""
        SELECT av FROM AgentVersionEntity av 
        WHERE av.agentId = :agentId 
        AND av.tenantId = :tenantId 
        AND av.version = :version
        """)
    Optional<AgentVersionEntity> findByAgentIdAndTenantIdAndVersion(
        @Param("agentId") UUID agentId,
        @Param("tenantId") UUID tenantId,
        @Param("version") Integer version
    );
}

package com.agenthub.orchestrator.repository;

import com.agenthub.orchestrator.entity.AgentVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AgentVersion entities.
 *
 * <p>Schema-based multi-tenancy is enforced by Hibernate at the connection level;
 * no explicit {@code tenant_id} filter is needed in queries.
 *
 * @since 1.0.0
 */
@Repository
public interface AgentVersionRepository extends JpaRepository<AgentVersionEntity, UUID> {

    /**
     * Returns the latest published version for a given agent.
     */
    @Query("""
        SELECT av FROM AgentVersionEntity av
        WHERE av.agentId = :agentId
        AND av.status = 'PUBLISHED'
        ORDER BY av.version DESC
        LIMIT 1
        """)
    Optional<AgentVersionEntity> findLatestPublishedByAgentId(@Param("agentId") UUID agentId);

    /**
     * Returns a specific version number for a given agent.
     */
    @Query("""
        SELECT av FROM AgentVersionEntity av
        WHERE av.agentId = :agentId
        AND av.version = :version
        """)
    Optional<AgentVersionEntity> findByAgentIdAndVersion(
        @Param("agentId") UUID agentId,
        @Param("version") Integer version
    );
}

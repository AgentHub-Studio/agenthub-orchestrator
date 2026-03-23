package com.agenthub.orchestrator.domain.port;

import com.agenthub.orchestrator.adapter.out.persistence.ExecutionEntity;
import com.agenthub.orchestrator.adapter.in.rest.ExecutionSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for Execution entities.
 *
 * <p>Schema-based multi-tenancy is enforced by Hibernate at the connection level;
 * no explicit {@code tenant_id} filter is needed in queries.
 *
 * @since 1.0.0
 */
@Repository
public interface ExecutionRepository extends JpaRepository<ExecutionEntity, UUID> {

    /**
     * Returns all execution summaries for the current tenant schema, ordered by creation date.
     */
    @Query("""
        SELECT e.id as id, e.agentId as agentId,
               e.agentVersionId as agentVersionId, e.status as status,
               e.triggerType as triggerType, e.startedAt as startedAt,
               e.finishedAt as finishedAt, e.createdAt as createdAt
        FROM ExecutionEntity e
        ORDER BY e.createdAt DESC
        """)
    Page<ExecutionSummary> findAllSummaries(Pageable pageable);

    /**
     * Returns execution summaries filtered by status.
     */
    @Query("""
        SELECT e.id as id, e.agentId as agentId,
               e.agentVersionId as agentVersionId, e.status as status,
               e.triggerType as triggerType, e.startedAt as startedAt,
               e.finishedAt as finishedAt, e.createdAt as createdAt
        FROM ExecutionEntity e
        WHERE e.status = :status
        ORDER BY e.createdAt DESC
        """)
    Page<ExecutionSummary> findSummariesByStatus(
        @Param("status") String status,
        Pageable pageable
    );

    /**
     * Returns execution summaries for a specific agent.
     */
    @Query("""
        SELECT e.id as id, e.agentId as agentId,
               e.agentVersionId as agentVersionId, e.status as status,
               e.triggerType as triggerType, e.startedAt as startedAt,
               e.finishedAt as finishedAt, e.createdAt as createdAt
        FROM ExecutionEntity e
        WHERE e.agentId = :agentId
        ORDER BY e.createdAt DESC
        """)
    Page<ExecutionSummary> findSummariesByAgentId(
        @Param("agentId") UUID agentId,
        Pageable pageable
    );

    /**
     * Counts executions currently in PENDING or RUNNING state.
     */
    @Query("""
        SELECT COUNT(e) FROM ExecutionEntity e
        WHERE e.status IN ('PENDING', 'RUNNING')
        """)
    long countRunningExecutions();
}

package com.agenthub.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Entity for AGENT_EXECUTION table
 * 
 * Represents an execution of an agent pipeline.
 * Contains the execution state, input, output, and timing information.
 * 
 * @since 1.0.0
 */
@Entity
@Table(name = "agent_execution", schema = "agenthub", indexes = {
    @Index(name = "idx_execution_tenant", columnList = "tenant_id"),
    @Index(name = "idx_execution_agent", columnList = "agent_id"),
    @Index(name = "idx_execution_status", columnList = "status"),
    @Index(name = "idx_execution_started", columnList = "started_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionEntity implements Persistable<UUID> {
    
    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;
    
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;
    
    @Column(name = "agent_version_id", nullable = false)
    private UUID agentVersionId;
    
    @Column(name = "trigger_type", length = 50, nullable = false)
    private String triggerType; // MANUAL, SCHEDULED, API, WEBHOOK
    
    @Column(name = "triggered_by")
    private UUID triggeredBy;
    
    @Column(name = "status", length = 30, nullable = false)
    private String status; // PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    
    @Type(JsonBinaryType.class)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private Map<String, Object> inputJson;
    
    @Type(JsonBinaryType.class)
    @Column(name = "context_json", columnDefinition = "jsonb")
    private Map<String, Object> contextJson;
    
    @Type(JsonBinaryType.class)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private Map<String, Object> resultJson;
    
    @Column(name = "started_at")
    private Instant startedAt;
    
    @Column(name = "finished_at")
    private Instant finishedAt;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NodeExecutionEntity> nodeExecutions = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (status == null) {
            status = "PENDING";
        }
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return this.isNew;
    }
    
    /**
     * Calculate execution duration in milliseconds
     */
    public Long getDurationMs() {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
    
    /**
     * Check if execution is in a terminal state
     */
    public boolean isTerminal() {
        return "COMPLETED".equals(status) 
            || "FAILED".equals(status) 
            || "CANCELLED".equals(status);
    }
    
    /**
     * Check if execution is still running
     */
    public boolean isRunning() {
        return "RUNNING".equals(status) || "PENDING".equals(status);
    }
}

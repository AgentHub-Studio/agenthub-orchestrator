package com.agenthub.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Entity for AGENT_EXECUTION_NODE table
 * 
 * Represents the execution of a single node within a pipeline.
 * Tracks status, timing, input/output, and errors for each node.
 * 
 * @since 1.0.0
 */
@Entity
@Table(name = "agent_execution_node", indexes = {
    @Index(name = "idx_exec_node_execution", columnList = "execution_id"),
    @Index(name = "idx_exec_node_pipeline", columnList = "pipeline_node_id"),
    @Index(name = "idx_exec_node_status", columnList = "status"),
    @Index(name = "idx_exec_node_started", columnList = "started_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NodeExecutionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private ExecutionEntity execution;
    
    @Column(name = "pipeline_node_id")
    private UUID pipelineNodeId;
    
    @Column(name = "parent_node_id")
    private UUID parentNodeId;
    
    @Column(name = "node_type", length = 50, nullable = false)
    private String nodeType; // INPUT, OUTPUT, TRANSFORM, LLM, HTTP, etc.
    
    @Column(name = "node_name", length = 200, nullable = false)
    private String nodeName;
    
    @Column(name = "status", length = 30, nullable = false)
    private String status; // PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    
    @Type(JsonBinaryType.class)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private Map<String, Object> inputJson;
    
    @Type(JsonBinaryType.class)
    @Column(name = "output_json", columnDefinition = "jsonb")
    private Map<String, Object> outputJson;
    
    @Column(name = "started_at")
    private Instant startedAt;
    
    @Column(name = "finished_at")
    private Instant finishedAt;
    
    @Column(name = "latency_ms")
    private Long latencyMs;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (status == null) {
            status = "PENDING";
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        // Calculate latency when finished
        if (startedAt != null && finishedAt != null && latencyMs == null) {
            latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }
    
    /**
     * Mark node as running
     */
    public void markRunning() {
        this.status = "RUNNING";
        this.startedAt = Instant.now();
    }
    
    /**
     * Mark node as completed
     */
    public void markCompleted(Map<String, Object> output) {
        this.status = "COMPLETED";
        this.finishedAt = Instant.now();
        this.outputJson = output;
        calculateLatency();
    }
    
    /**
     * Mark node as failed
     */
    public void markFailed(String error) {
        this.status = "FAILED";
        this.finishedAt = Instant.now();
        this.errorMessage = error;
        calculateLatency();
    }
    
    /**
     * Mark node as skipped
     */
    public void markSkipped(String reason) {
        this.status = "SKIPPED";
        this.finishedAt = Instant.now();
        this.errorMessage = reason;
        calculateLatency();
    }
    
    private void calculateLatency() {
        if (startedAt != null && finishedAt != null) {
            latencyMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
    }
}

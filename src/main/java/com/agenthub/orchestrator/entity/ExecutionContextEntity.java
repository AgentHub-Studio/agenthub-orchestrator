package com.agenthub.orchestrator.entity;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Snapshot entity for execution context.
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "execution_context", schema = "agenthub", indexes = {
    @Index(name = "idx_exec_ctx_execution", columnList = "execution_id"),
    @Index(name = "idx_exec_ctx_updated", columnList = "updated_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "execution_id", nullable = false)
    private UUID executionId;

    @Type(JsonBinaryType.class)
    @Column(name = "context_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> contextJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }
}

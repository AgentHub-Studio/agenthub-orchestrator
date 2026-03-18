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
 * Entity for AGENT_VERSION table
 * 
 * Represents a version of an agent with its pipeline definition.
 * Each version is immutable after publication.
 * 
 * @since 1.0.0
 */
@Entity
@Table(name = "agent_version", indexes = {
    @Index(name = "idx_agent_version_agent", columnList = "agent_id"),
    @Index(name = "idx_agent_version_status", columnList = "status"),
    @Index(name = "idx_agent_version_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AgentVersionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;
    
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;
    
    @Column(name = "version", nullable = false)
    private Integer version;
    
    @Column(name = "status", length = 30, nullable = false)
    private String status; // DRAFT, PUBLISHED, ARCHIVED
    
    @Column(name = "llm_config_preset_id")
    private UUID llmConfigPresetId;
    
    @Type(JsonBinaryType.class)
    @Column(name = "model_config_json", columnDefinition = "jsonb")
    private Map<String, Object> modelConfigJson;
    
    @Type(JsonBinaryType.class)
    @Column(name = "pipeline_definition_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> pipelineDefinitionJson;
    
    @Type(JsonBinaryType.class)
    @Column(name = "variables_json", columnDefinition = "jsonb")
    private Map<String, Object> variablesJson;
    
    @Column(name = "created_by")
    private UUID createdBy;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}

package com.agenthub.orchestrator.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection for agent version listing.
 *
 * @since 1.0.0
 */
public interface AgentVersionSummary {

    UUID getId();

    UUID getAgentId();

    UUID getTenantId();

    Integer getVersion();

    String getStatus();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}

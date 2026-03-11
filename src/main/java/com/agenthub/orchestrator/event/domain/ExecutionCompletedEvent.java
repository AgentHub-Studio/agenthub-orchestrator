package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Execution completed event.
 *
 * @since 1.0.0
 */
public record ExecutionCompletedEvent(UUID executionId, UUID tenantId) {}

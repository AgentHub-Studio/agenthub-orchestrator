package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Execution queued event.
 *
 * @since 1.0.0
 */
public record ExecutionQueuedEvent(UUID executionId, UUID tenantId) {}

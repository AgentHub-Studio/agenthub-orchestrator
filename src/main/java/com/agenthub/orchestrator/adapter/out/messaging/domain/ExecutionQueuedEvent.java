package com.agenthub.orchestrator.adapter.out.messaging.domain;

import java.util.UUID;

/**
 * Execution queued event.
 *
 * @since 1.0.0
 */
public record ExecutionQueuedEvent(UUID executionId, UUID tenantId) {}

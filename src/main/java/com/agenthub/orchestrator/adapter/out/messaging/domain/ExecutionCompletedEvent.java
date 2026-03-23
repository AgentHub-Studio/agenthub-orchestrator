package com.agenthub.orchestrator.adapter.out.messaging.domain;

import java.util.UUID;

/**
 * Execution completed event.
 *
 * @since 1.0.0
 */
public record ExecutionCompletedEvent(UUID executionId, UUID tenantId) {}

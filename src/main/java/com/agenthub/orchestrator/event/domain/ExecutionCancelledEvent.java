package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Execution cancelled event.
 *
 * @since 1.0.0
 */
public record ExecutionCancelledEvent(UUID executionId, UUID tenantId) {}

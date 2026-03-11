package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Execution timed out event.
 *
 * @since 1.0.0
 */
public record ExecutionTimedOutEvent(UUID executionId, UUID tenantId) {}

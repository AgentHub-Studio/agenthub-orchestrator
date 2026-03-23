package com.agenthub.orchestrator.adapter.out.messaging.domain;

import java.util.UUID;

/**
 * Execution timed out event.
 *
 * @since 1.0.0
 */
public record ExecutionTimedOutEvent(UUID executionId, UUID tenantId) {}

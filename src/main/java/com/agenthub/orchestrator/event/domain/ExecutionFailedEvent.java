package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Execution failed event.
 *
 * @since 1.0.0
 */
public record ExecutionFailedEvent(UUID executionId, UUID tenantId, String error) {}

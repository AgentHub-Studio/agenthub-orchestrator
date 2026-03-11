package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Execution started event.
 *
 * @since 1.0.0
 */
public record ExecutionStartedEvent(UUID executionId, UUID tenantId) {}

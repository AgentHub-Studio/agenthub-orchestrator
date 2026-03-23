package com.agenthub.orchestrator.adapter.out.messaging.domain;

import java.util.UUID;

/**
 * Execution failed event.
 *
 * @since 1.0.0
 */
public record ExecutionFailedEvent(UUID executionId, UUID tenantId, String error) {}

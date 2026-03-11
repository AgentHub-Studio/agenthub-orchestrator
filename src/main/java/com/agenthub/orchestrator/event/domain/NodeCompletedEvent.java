package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Node completed event.
 *
 * @since 1.0.0
 */
public record NodeCompletedEvent(UUID executionId, String nodeId) {}

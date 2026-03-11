package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Node failed event.
 *
 * @since 1.0.0
 */
public record NodeFailedEvent(UUID executionId, String nodeId, String error) {}

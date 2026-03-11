package com.agenthub.orchestrator.event.domain;

import java.util.UUID;

/**
 * Node started event.
 *
 * @since 1.0.0
 */
public record NodeStartedEvent(UUID executionId, String nodeId) {}

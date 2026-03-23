package com.agenthub.orchestrator.adapter.out.messaging.domain;

import java.util.UUID;

/**
 * Node started event.
 *
 * @since 1.0.0
 */
public record NodeStartedEvent(UUID executionId, String nodeId) {}

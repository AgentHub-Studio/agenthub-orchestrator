package com.agenthub.orchestrator.adapter.out.messaging.domain;

import java.util.UUID;

/**
 * Node completed event.
 *
 * @since 1.0.0
 */
public record NodeCompletedEvent(UUID executionId, String nodeId) {}

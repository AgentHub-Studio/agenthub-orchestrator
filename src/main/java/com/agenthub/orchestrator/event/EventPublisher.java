package com.agenthub.orchestrator.event;

/**
 * Publishes orchestration events.
 *
 * @since 1.0.0
 */
public interface EventPublisher {

    void publish(String eventType, Object payload);
}

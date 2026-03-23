package com.agenthub.orchestrator.adapter.out.messaging;

/**
 * Publishes orchestration events.
 *
 * @since 1.0.0
 */
public interface EventPublisher {

    void publish(String eventType, Object payload);
}

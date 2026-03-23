package com.agenthub.orchestrator.adapter.out.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Temporary event publisher that logs events.
 *
 * @since 1.0.0
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(String eventType, Object payload) {
        log.info("event={} payload={}", eventType, payload);
    }
}

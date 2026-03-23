package com.agenthub.orchestrator.adapter.out.messaging;

import com.agenthub.orchestrator.adapter.out.messaging.domain.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for event publisher implementations and typed event payloads.
 *
 * @since 1.0.0
 */
class EventPublisherTest {

    @Test
    void loggingPublisherShouldAcceptAllEvents() {
        EventPublisher publisher = new LoggingEventPublisher();
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        // Should not throw
        assertDoesNotThrow(() -> {
            publisher.publish("execution.queued", new ExecutionQueuedEvent(executionId, tenantId));
            publisher.publish("execution.started", new ExecutionStartedEvent(executionId, tenantId));
            publisher.publish("execution.completed", new ExecutionCompletedEvent(executionId, tenantId));
            publisher.publish("execution.failed", new ExecutionFailedEvent(executionId, tenantId, "error"));
            publisher.publish("execution.cancelled", new ExecutionCancelledEvent(executionId, tenantId));
            publisher.publish("execution.timed_out", new ExecutionTimedOutEvent(executionId, tenantId));
            publisher.publish("node.started", new NodeStartedEvent(executionId, "node1"));
            publisher.publish("node.completed", new NodeCompletedEvent(executionId, "node1"));
            publisher.publish("node.failed", new NodeFailedEvent(executionId, "node1", "error"));
        });
    }

    @Test
    void executionQueuedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        ExecutionQueuedEvent event = new ExecutionQueuedEvent(executionId, tenantId);

        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
    }

    @Test
    void executionStartedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        ExecutionStartedEvent event = new ExecutionStartedEvent(executionId, tenantId);

        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
    }

    @Test
    void executionCompletedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        ExecutionCompletedEvent event = new ExecutionCompletedEvent(executionId, tenantId);

        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
    }

    @Test
    void executionFailedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String errorMessage = "Pipeline execution failed";

        ExecutionFailedEvent event = new ExecutionFailedEvent(executionId, tenantId, errorMessage);

        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
        assertEquals(errorMessage, event.error());
    }

    @Test
    void executionCancelledEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        ExecutionCancelledEvent event = new ExecutionCancelledEvent(executionId, tenantId);

        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
    }

    @Test
    void executionTimedOutEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        ExecutionTimedOutEvent event = new ExecutionTimedOutEvent(executionId, tenantId);

        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
    }

    @Test
    void nodeStartedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        String nodeId = "transform-1";

        NodeStartedEvent event = new NodeStartedEvent(executionId, nodeId);

        assertEquals(executionId, event.executionId());
        assertEquals(nodeId, event.nodeId());
    }

    @Test
    void nodeCompletedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        String nodeId = "transform-1";

        NodeCompletedEvent event = new NodeCompletedEvent(executionId, nodeId);

        assertEquals(executionId, event.executionId());
        assertEquals(nodeId, event.nodeId());
    }

    @Test
    void nodeFailedEventShouldContainCorrectPayload() {
        UUID executionId = UUID.randomUUID();
        String nodeId = "llm-1";
        String errorMessage = "LLM timeout";

        NodeFailedEvent event = new NodeFailedEvent(executionId, nodeId, errorMessage);

        assertEquals(executionId, event.executionId());
        assertEquals(nodeId, event.nodeId());
        assertEquals(errorMessage, event.error());
    }

    @Test
    void capturingPublisherShouldRecordAllEvents() {
        CapturingEventPublisher publisher = new CapturingEventPublisher();
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        publisher.publish("execution.queued", new ExecutionQueuedEvent(executionId, tenantId));
        publisher.publish("execution.started", new ExecutionStartedEvent(executionId, tenantId));
        publisher.publish("node.started", new NodeStartedEvent(executionId, "input"));
        publisher.publish("node.completed", new NodeCompletedEvent(executionId, "input"));
        publisher.publish("execution.completed", new ExecutionCompletedEvent(executionId, tenantId));

        assertEquals(5, publisher.events.size());
        assertEquals("execution.queued", publisher.events.get(0).eventType());
        assertEquals("execution.started", publisher.events.get(1).eventType());
        assertEquals("node.started", publisher.events.get(2).eventType());
        assertEquals("node.completed", publisher.events.get(3).eventType());
        assertEquals("execution.completed", publisher.events.get(4).eventType());

        assertTrue(publisher.events.get(0).payload() instanceof ExecutionQueuedEvent);
        assertTrue(publisher.events.get(1).payload() instanceof ExecutionStartedEvent);
        assertTrue(publisher.events.get(2).payload() instanceof NodeStartedEvent);
        assertTrue(publisher.events.get(3).payload() instanceof NodeCompletedEvent);
        assertTrue(publisher.events.get(4).payload() instanceof ExecutionCompletedEvent);
    }

    @Test
    void eventTypesShouldFollowNamingConvention() {
        // Verify that all events follow the "category.action" pattern
        String[] validEventTypes = {
            "execution.queued",
            "execution.started",
            "execution.completed",
            "execution.failed",
            "execution.cancelled",
            "execution.timed_out",
            "node.started",
            "node.completed",
            "node.failed"
        };

        for (String eventType : validEventTypes) {
            assertTrue(eventType.matches("^[a-z_]+\\.[a-z_]+$"),
                "Event type should follow 'category.action' pattern: " + eventType);
        }
    }

    @Test
    void recordsShouldBeImmutable() {
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        ExecutionStartedEvent event = new ExecutionStartedEvent(executionId, tenantId);

        // Records are immutable - verify getters work
        assertNotNull(event.executionId());
        assertNotNull(event.tenantId());
        assertEquals(executionId, event.executionId());
        assertEquals(tenantId, event.tenantId());
    }

    // Helper class for testing
    private static class CapturingEventPublisher implements EventPublisher {
        record CapturedEvent(String eventType, Object payload) {}

        final List<CapturedEvent> events = new ArrayList<>();

        @Override
        public void publish(String eventType, Object payload) {
            events.add(new CapturedEvent(eventType, payload));
        }
    }
}

package com.agenthub.orchestrator.service.metrics;

import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Centralized metrics for pipeline execution observability.
 * <p>
 * Exposes the following metrics to Micrometer (and Prometheus via actuator):
 * <ul>
 *   <li>{@code orchestrator.executions.total} — Counter by status</li>
 *   <li>{@code orchestrator.executions.duration} — Timer by status</li>
 *   <li>{@code orchestrator.nodes.total} — Counter by node type and status</li>
 *   <li>{@code orchestrator.nodes.duration} — Timer by node type</li>
 *   <li>{@code orchestrator.events.published.total} — Counter by event type</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Component
public class ExecutionMetrics {

    private static final String EXECUTION_COUNTER = "orchestrator.executions.total";
    private static final String EXECUTION_TIMER = "orchestrator.executions.duration";
    private static final String NODE_COUNTER = "orchestrator.nodes.total";
    private static final String NODE_TIMER = "orchestrator.nodes.duration";
    private static final String EVENT_COUNTER = "orchestrator.events.published.total";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> executionCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> executionTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> nodeCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> nodeTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> eventCounters = new ConcurrentHashMap<>();

    public ExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records a completed execution with its final status and duration.
     *
     * @param status   the terminal execution status
     * @param duration the total execution duration
     */
    public void recordExecution(ExecutionStatus status, Duration duration) {
        String statusTag = status.name().toLowerCase();

        executionCounters.computeIfAbsent(statusTag, s ->
            Counter.builder(EXECUTION_COUNTER)
                .tag("status", s)
                .description("Total number of pipeline executions by status")
                .register(registry)
        ).increment();

        if (duration != null) {
            executionTimers.computeIfAbsent(statusTag, s ->
                Timer.builder(EXECUTION_TIMER)
                    .tag("status", s)
                    .description("Pipeline execution duration by status")
                    .register(registry)
            ).record(duration);
        }
    }

    /**
     * Records a node execution with its type, outcome, and duration.
     *
     * @param nodeType the type of node (LLM, HTTP, TRANSFORM, etc.)
     * @param success  whether the node completed successfully
     * @param duration the node execution duration
     */
    public void recordNodeExecution(String nodeType, boolean success, Duration duration) {
        String typeTag = nodeType.toLowerCase();
        String statusTag = success ? "success" : "failure";
        String key = typeTag + ":" + statusTag;

        nodeCounters.computeIfAbsent(key, k ->
            Counter.builder(NODE_COUNTER)
                .tag("type", typeTag)
                .tag("status", statusTag)
                .description("Total number of node executions by type and status")
                .register(registry)
        ).increment();

        if (duration != null) {
            nodeTimers.computeIfAbsent(typeTag, t ->
                Timer.builder(NODE_TIMER)
                    .tag("type", t)
                    .description("Node execution duration by type")
                    .register(registry)
            ).record(duration);
        }
    }

    /**
     * Records a published event.
     *
     * @param eventType the event routing key (e.g. "execution.completed")
     */
    public void recordEventPublished(String eventType) {
        eventCounters.computeIfAbsent(eventType, t ->
            Counter.builder(EVENT_COUNTER)
                .tag("type", t)
                .description("Total number of events published by type")
                .register(registry)
        ).increment();
    }
}

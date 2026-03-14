package com.agenthub.orchestrator.event;

import com.agenthub.orchestrator.service.AgentExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Listens to execution.requested events from backend via RabbitMQ.
 * 
 * <p>Consumes agent execution requests from the priority queue and triggers
 * pipeline execution in the orchestrator.
 * 
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agenthub.orchestrator.execution-queue.enabled", havingValue = "true")
public class AgentExecutionQueueListener {

    private final AgentExecutionService executionService;

    /**
     * Handle execution.requested event.
     * 
     * <p>This is triggered when backend publishes an agent execution request.
     * The queue is a priority queue, so higher priority executions are processed first.
     */
    @RabbitListener(queues = "${agenthub.orchestrator.execution-queue.name:agenthub.execution.queue}")
    public void onExecutionRequested(AgentExecutionRequestedEvent event) {
        log.info("Received execution.requested event: executionId={}, tenantId={}, agentId={}, priority={}", 
                event.executionId(), event.tenantId(), event.agentId(), event.priority());
        
        try {
            // Start execution asynchronously
            executionService.startExecution(
                event.executionId(),
                event.tenantId(),
                event.agentId(),
                event.userId(),
                event.inputData(),
                event.triggerSource()
            ).subscribe(
                result -> log.info("Execution completed: executionId={}, status={}", 
                        event.executionId(), result.status()),
                error -> log.error("Execution failed: executionId={}", event.executionId(), error),
                () -> log.debug("Execution processing finished: executionId={}", event.executionId())
            );
            
        } catch (Exception e) {
            log.error("Failed to process execution.requested event: executionId={}", 
                    event.executionId(), e);
            // Exception is logged but not re-thrown to avoid message requeue
            // Dead Letter Queue will handle persistent failures
        }
    }

    /**
     * Event DTO matching backend's AgentExecutionRequestedEvent.
     */
    public record AgentExecutionRequestedEvent(
        UUID executionId,
        UUID tenantId,
        UUID agentId,
        UUID userId,
        Map<String, Object> inputData,
        Integer priority,
        String triggerSource
    ) {}
}

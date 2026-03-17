package com.agenthub.orchestrator.event;

import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.service.agent.AgentExecutionService;
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
            // Build command from event fields
            StartExecutionCommand command = new StartExecutionCommand(
                event.tenantId(),
                event.agentId(),
                null,
                event.inputData(),
                StartExecutionCommand.ExecutionMode.ASYNC,
                null,
                event.userId()
            );

            // Start execution asynchronously
            executionService.startExecution(command)
                .thenAccept(executionId -> log.info("Execution queued: executionId={}", executionId))
                .exceptionally(error -> {
                    log.error("Execution failed to queue: requestedExecutionId={}", event.executionId(), error);
                    return null;
                });
            
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

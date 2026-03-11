package com.agenthub.orchestrator.service.agent;

import com.agenthub.orchestrator.domain.execution.ExecutionState;
import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import com.agenthub.orchestrator.domain.execution.NodeResult;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.dto.AgentExecutionResult;
import com.agenthub.orchestrator.dto.AgentExecutionStatus;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.event.EventPublisher;
import com.agenthub.orchestrator.event.domain.ExecutionCancelledEvent;
import com.agenthub.orchestrator.event.domain.ExecutionCompletedEvent;
import com.agenthub.orchestrator.event.domain.ExecutionFailedEvent;
import com.agenthub.orchestrator.event.domain.ExecutionQueuedEvent;
import com.agenthub.orchestrator.event.domain.ExecutionStartedEvent;
import com.agenthub.orchestrator.event.domain.ExecutionTimedOutEvent;
import com.agenthub.orchestrator.event.domain.NodeCompletedEvent;
import com.agenthub.orchestrator.event.domain.NodeFailedEvent;
import com.agenthub.orchestrator.event.domain.NodeStartedEvent;
import com.agenthub.orchestrator.exception.InvalidPipelineException;
import com.agenthub.orchestrator.executor.ExecutionContext;
import com.agenthub.orchestrator.executor.NodeExecutionResult;
import com.agenthub.orchestrator.executor.NodeExecutor;
import com.agenthub.orchestrator.executor.NodeExecutorRegistry;
import com.agenthub.orchestrator.service.execution.ExecutionStateService;
import com.agenthub.orchestrator.service.pipeline.PipelineDefinitionService;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import com.agenthub.orchestrator.service.scheduler.NodeScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Agent execution orchestrator implementation.
 *
 * @since 1.0.0
 */
@Service
public class AgentExecutionServiceImpl implements AgentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionServiceImpl.class);

    private final PipelineDefinitionService pipelineDefinitionService;
    private final ExecutionStateService executionStateService;
    private final NodeScheduler nodeScheduler;
    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final EventPublisher eventPublisher;

    public AgentExecutionServiceImpl(
        PipelineDefinitionService pipelineDefinitionService,
        ExecutionStateService executionStateService,
        NodeScheduler nodeScheduler,
        NodeExecutorRegistry nodeExecutorRegistry,
        EventPublisher eventPublisher
    ) {
        this.pipelineDefinitionService = pipelineDefinitionService;
        this.executionStateService = executionStateService;
        this.nodeScheduler = nodeScheduler;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public CompletableFuture<UUID> startExecution(StartExecutionCommand command) {
        PipelineDefinition pipeline = loadPipeline(command);
        validatePipeline(pipeline);

        UUID effectiveVersionId = command.agentVersionId() != null ? command.agentVersionId() : pipeline.id();
        ExecutionState state = executionStateService.createExecution(
            command.tenantId(),
            command.agentId(),
            effectiveVersionId,
            command.input()
        );

        state.markAsQueued();
        executionStateService.saveExecution(state);
        eventPublisher.publish("execution.queued", new ExecutionQueuedEvent(state.getExecutionId(), state.getTenantId()));

        CompletableFuture.runAsync(() -> runPipeline(pipeline, state));

        return CompletableFuture.completedFuture(state.getExecutionId());
    }

    @Override
    public AgentExecutionResult executeSync(StartExecutionCommand command, Duration timeout) {
        UUID executionId = startExecution(command).join();
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofMillis(command.timeoutMs());
        long deadline = System.currentTimeMillis() + effectiveTimeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            ExecutionState state = executionStateService.loadExecution(executionId, command.tenantId());
            if (state.isTerminal()) {
                return toResult(state);
            }
            sleepQuietly(100);
        }

        ExecutionState timedOut = executionStateService.loadExecution(executionId, command.tenantId());
        timedOut.markAsTimedOut();
        executionStateService.saveExecution(timedOut);
        eventPublisher.publish("execution.timed_out", new ExecutionTimedOutEvent(executionId, command.tenantId()));
        return toResult(timedOut);
    }

    @Override
    public void cancelExecution(UUID executionId, UUID tenantId) {
        ExecutionState state = executionStateService.loadExecution(executionId, tenantId);
        if (state.getStatus().isCancellable()) {
            state.markAsCancelled();
            executionStateService.saveExecution(state);
            eventPublisher.publish("execution.cancelled", new ExecutionCancelledEvent(executionId, tenantId));
        }
    }

    @Override
    public UUID retryExecution(UUID executionId, UUID tenantId) {
        ExecutionState state = executionStateService.loadExecution(executionId, tenantId);

        StartExecutionCommand retryCommand = new StartExecutionCommand(
            state.getTenantId(),
            state.getAgentId(),
            state.getAgentVersionId(),
            state.getInput(),
            StartExecutionCommand.ExecutionMode.ASYNC,
            1800000L,
            null
        );

        return startExecution(retryCommand).join();
    }

    @Override
    public AgentExecutionStatus getExecutionStatus(UUID executionId, UUID tenantId) {
        ExecutionState state = executionStateService.loadExecution(executionId, tenantId);
        int progress = 0;
        try {
            PipelineDefinition pipeline = pipelineDefinitionService.loadPipelineDefinition(state.getAgentVersionId(), tenantId);
            progress = nodeScheduler.getProgress(pipeline, state);
        } catch (Exception ignored) {
            // best effort status endpoint
        }

        return new AgentExecutionStatus(
            state.getExecutionId(),
            state.getStatus(),
            progress,
            state.getStartedAt(),
            state.getCompletedAt(),
            state.getError()
        );
    }

    private void runPipeline(PipelineDefinition pipeline, ExecutionState state) {
        try {
            state.markAsRunning();
            executionStateService.saveExecution(state);
            eventPublisher.publish("execution.started", new ExecutionStartedEvent(state.getExecutionId(), state.getTenantId()));

            while (!nodeScheduler.isExecutionComplete(pipeline, state)) {
                List<String> readyNodes = nodeScheduler.getReadyNodes(pipeline, state);
                if (readyNodes.isEmpty()) {
                    break;
                }

                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String nodeId : readyNodes) {
                    futures.add(CompletableFuture.runAsync(() -> executeNode(pipeline, state, nodeId)));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            if (!state.getFailedNodes().isEmpty()) {
                state.markAsFailed("One or more nodes failed");
                eventPublisher.publish("execution.failed", new ExecutionFailedEvent(state.getExecutionId(), state.getTenantId(), state.getError()));
            } else {
                state.markAsCompleted();
                eventPublisher.publish("execution.completed", new ExecutionCompletedEvent(state.getExecutionId(), state.getTenantId()));
            }

            executionStateService.saveExecution(state);
        } catch (Exception e) {
            state.markAsFailed(e.getMessage());
            executionStateService.saveExecution(state);
            eventPublisher.publish("execution.failed", new ExecutionFailedEvent(state.getExecutionId(), state.getTenantId(), e.getMessage()));
            log.error("Execution failed: executionId={}", state.getExecutionId(), e);
        }
    }

    private void executeNode(PipelineDefinition pipeline, ExecutionState state, String nodeId) {
        PipelineNode node = pipeline.getNodeById(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Node not found in pipeline: " + nodeId));

        executionStateService.markNodeVisited(state.getExecutionId(), nodeId);
        int attemptNumber = executionStateService.incrementNodeAttempt(state.getExecutionId(), nodeId);

        OffsetDateTime startedAt = OffsetDateTime.now();
        eventPublisher.publish("node.started", new NodeStartedEvent(state.getExecutionId(), nodeId));

        ExecutionContext executionContext = new ExecutionContext(
            state.getExecutionId(),
            state.getTenantId(),
            state.getAgentId(),
            state.getInput()
        );

        // hydrate node results into current execution context
        for (Map.Entry<String, NodeResult> entry : state.getNodeResults().entrySet()) {
            if (entry.getValue().data() instanceof Map<?, ?> mapData) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) mapData;
                executionContext.setNodeResult(entry.getKey(), casted);
            }
        }

        NodeExecutor executor = nodeExecutorRegistry.getExecutor(node.type());
        NodeExecutionResult executionResult = executor.execute(node, executionContext, node.config()).join();
        OffsetDateTime completedAt = OffsetDateTime.now();

        if (executionResult.success()) {
            Object resultData = executionResult.output();
            Map<String, Object> namespacedResult = executionContext.getNodeResult(nodeId);
            if (namespacedResult != null && !namespacedResult.isEmpty()) {
                resultData = namespacedResult;
            }

            NodeResult result = NodeResult.success(
                nodeId,
                resultData,
                startedAt,
                completedAt,
                attemptNumber
            );
            executionStateService.markNodeCompleted(state.getExecutionId(), nodeId, result);
            eventPublisher.publish("node.completed", new NodeCompletedEvent(state.getExecutionId(), nodeId));
        } else {
            NodeResult result = NodeResult.failure(
                nodeId,
                executionResult.error(),
                startedAt,
                completedAt,
                attemptNumber
            );
            executionStateService.markNodeFailed(state.getExecutionId(), nodeId, result);
            eventPublisher.publish("node.failed", new NodeFailedEvent(state.getExecutionId(), nodeId, executionResult.error()));
        }
    }

    private PipelineDefinition loadPipeline(StartExecutionCommand command) {
        if (command.agentVersionId() != null) {
            return pipelineDefinitionService.loadPipelineDefinition(command.agentVersionId(), command.tenantId());
        }
        return pipelineDefinitionService.getActivePipeline(command.agentId(), command.tenantId());
    }

    private void validatePipeline(PipelineDefinition pipeline) {
        ValidationResult validation = pipelineDefinitionService.validatePipeline(pipeline);
        if (!validation.isValid()) {
            throw new InvalidPipelineException(validation.getErrors());
        }
    }

    private AgentExecutionResult toResult(ExecutionState state) {
        Long latency = null;
        if (state.getCompletedAt() != null) {
            latency = Duration.between(state.getStartedAt(), state.getCompletedAt()).toMillis();
        }

        Map<String, Object> output = Map.of(
            "nodeResults", state.getNodeResults(),
            "failedNodes", state.getFailedNodes(),
            "completedNodes", state.getCompletedNodes()
        );

        return new AgentExecutionResult(
            state.getExecutionId(),
            state.getStatus(),
            output,
            state.getError(),
            state.getStartedAt(),
            state.getCompletedAt(),
            latency
        );
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

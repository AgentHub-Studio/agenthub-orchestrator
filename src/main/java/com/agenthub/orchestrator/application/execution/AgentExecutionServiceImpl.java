package com.agenthub.orchestrator.application.execution;

import com.agenthub.orchestrator.domain.execution.model.ExecutionState;
import com.agenthub.orchestrator.domain.execution.model.ExecutionStatus;
import com.agenthub.orchestrator.domain.execution.model.NodeResult;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.adapter.in.rest.AgentExecutionResult;
import com.agenthub.orchestrator.adapter.in.rest.AgentExecutionStatus;
import com.agenthub.orchestrator.adapter.in.rest.StartExecutionCommand;
import com.agenthub.orchestrator.adapter.out.messaging.EventPublisher;
import com.agenthub.orchestrator.adapter.out.messaging.domain.ExecutionCancelledEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.ExecutionCompletedEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.ExecutionFailedEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.ExecutionQueuedEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.ExecutionStartedEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.ExecutionTimedOutEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.NodeCompletedEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.NodeFailedEvent;
import com.agenthub.orchestrator.adapter.out.messaging.domain.NodeStartedEvent;
import com.agenthub.orchestrator.domain.exception.InvalidPipelineException;
import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.executor.NodeExecutor;
import com.agenthub.orchestrator.application.executor.NodeExecutorRegistry;
import com.agenthub.orchestrator.application.execution.ExecutionStateService;
import com.agenthub.orchestrator.application.metrics.ExecutionMetrics;
import com.agenthub.orchestrator.shared.multitenant.TenantContext;
import com.agenthub.orchestrator.shared.multitenant.TenantContextHolder;
import com.agenthub.orchestrator.application.pipeline.PipelineDefinitionService;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import com.agenthub.orchestrator.application.scheduler.NodeScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
    private final ExecutionMetrics executionMetrics;

    public AgentExecutionServiceImpl(
        PipelineDefinitionService pipelineDefinitionService,
        ExecutionStateService executionStateService,
        NodeScheduler nodeScheduler,
        NodeExecutorRegistry nodeExecutorRegistry,
        EventPublisher eventPublisher,
        ExecutionMetrics executionMetrics
    ) {
        this.pipelineDefinitionService = pipelineDefinitionService;
        this.executionStateService = executionStateService;
        this.nodeScheduler = nodeScheduler;
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.eventPublisher = eventPublisher;
        this.executionMetrics = executionMetrics;
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
        publishEvent("execution.queued", new ExecutionQueuedEvent(state.getExecutionId(), state.getTenantId()));

        TenantContext capturedContext = TenantContextHolder.getContext();
        CompletableFuture.runAsync(() -> {
            TenantContextHolder.setContext(capturedContext);
            try {
                runPipeline(pipeline, state);
            } finally {
                TenantContextHolder.clear();
            }
        });

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
            publishEvent("execution.timed_out", new ExecutionTimedOutEvent(executionId, command.tenantId()));
        return toResult(timedOut);
    }

    @Override
    public void cancelExecution(UUID executionId, UUID tenantId) {
        ExecutionState state = executionStateService.loadExecution(executionId, tenantId);
        if (state.getStatus().isCancellable()) {
            state.markAsCancelled();
            executionStateService.saveExecution(state);
            publishEvent("execution.cancelled", new ExecutionCancelledEvent(executionId, tenantId));
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
        MDC.put("tenantId", state.getTenantId().toString());
        MDC.put("executionId", state.getExecutionId().toString());
        OffsetDateTime pipelineStartedAt = OffsetDateTime.now();
        try {
            state.markAsRunning();
            executionStateService.saveExecution(state);
            publishEvent("execution.started", new ExecutionStartedEvent(state.getExecutionId(), state.getTenantId()));

            while (!nodeScheduler.isExecutionComplete(pipeline, state)) {
                List<String> readyNodes = nodeScheduler.getReadyNodes(pipeline, state);
                if (readyNodes.isEmpty()) {
                    break;
                }

                // Capture MDC and tenant context for propagation to async threads
                Map<String, String> mdcContext = MDC.getCopyOfContextMap();
                TenantContext nodeContext = TenantContextHolder.getContext();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String nodeId : readyNodes) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        if (mdcContext != null) {
                            MDC.setContextMap(mdcContext);
                        }
                        TenantContextHolder.setContext(nodeContext);
                        try {
                            executeNode(pipeline, state, nodeId);
                        } finally {
                            MDC.clear();
                            TenantContextHolder.clear();
                        }
                    }));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            if (!state.getFailedNodes().isEmpty()) {
                state.markAsFailed("One or more nodes failed");
                publishEvent("execution.failed", new ExecutionFailedEvent(state.getExecutionId(), state.getTenantId(), state.getError()));
            } else {
                state.markAsCompleted();
                publishEvent("execution.completed", new ExecutionCompletedEvent(state.getExecutionId(), state.getTenantId()));
            }

            executionStateService.saveExecution(state);
            executionMetrics.recordExecution(state.getStatus(), Duration.between(pipelineStartedAt, OffsetDateTime.now()));
        } catch (Exception e) {
            state.markAsFailed(e.getMessage());
            executionStateService.saveExecution(state);
            publishEvent("execution.failed", new ExecutionFailedEvent(state.getExecutionId(), state.getTenantId(), e.getMessage()));
            executionMetrics.recordExecution(ExecutionStatus.FAILED, Duration.between(pipelineStartedAt, OffsetDateTime.now()));
            log.error("Execution failed: executionId={}", state.getExecutionId(), e);
        } finally {
            MDC.clear();
        }
    }

    private void executeNode(PipelineDefinition pipeline, ExecutionState state, String nodeId) {
        MDC.put("nodeId", nodeId);
        PipelineNode node = pipeline.getNodeById(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Node not found in pipeline: " + nodeId));

        executionStateService.markNodeVisited(state.getExecutionId(), nodeId);
        int attemptNumber = executionStateService.incrementNodeAttempt(state.getExecutionId(), nodeId);

        OffsetDateTime startedAt = OffsetDateTime.now();
        publishEvent("node.started", new NodeStartedEvent(state.getExecutionId(), nodeId));

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

        Duration nodeDuration = Duration.between(startedAt, completedAt);
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
            executionMetrics.recordNodeExecution(node.type().name(), true, nodeDuration);
            publishEvent("node.completed", new NodeCompletedEvent(state.getExecutionId(), nodeId));
        } else {
            NodeResult result = NodeResult.failure(
                nodeId,
                executionResult.error(),
                startedAt,
                completedAt,
                attemptNumber
            );
            executionStateService.markNodeFailed(state.getExecutionId(), nodeId, result);
            executionMetrics.recordNodeExecution(node.type().name(), false, nodeDuration);
            publishEvent("node.failed", new NodeFailedEvent(state.getExecutionId(), nodeId, executionResult.error()));
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

    /**
     * Publishes an event and records the metric.
     */
    private void publishEvent(String eventType, Object payload) {
        eventPublisher.publish(eventType, payload);
        executionMetrics.recordEventPublished(eventType);
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

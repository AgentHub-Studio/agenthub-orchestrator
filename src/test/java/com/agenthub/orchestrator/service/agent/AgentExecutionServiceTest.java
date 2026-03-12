package com.agenthub.orchestrator.service.agent;

import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineEdge;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.dto.AgentExecutionResult;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.event.EventPublisher;
import com.agenthub.orchestrator.executor.NodeExecutorRegistry;
import com.agenthub.orchestrator.executor.impl.InputNodeExecutor;
import com.agenthub.orchestrator.executor.impl.OutputNodeExecutor;
import com.agenthub.orchestrator.executor.impl.TransformNodeExecutor;
import com.agenthub.orchestrator.service.execution.ExecutionStateService;
import com.agenthub.orchestrator.service.execution.ExecutionStateServiceImpl;
import com.agenthub.orchestrator.service.metrics.ExecutionMetrics;
import com.agenthub.orchestrator.service.pipeline.PipelineDefinitionService;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import com.agenthub.orchestrator.service.scheduler.NodeScheduler;
import com.agenthub.orchestrator.service.scheduler.NodeSchedulerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for AgentExecutionService.
 *
 * @since 1.0.0
 */
class AgentExecutionServiceTest {

    @Test
    void shouldExecuteSimplePipelineSync() {
        // Given
        PipelineDefinition pipeline = createSimplePipeline();
        PipelineDefinitionService pipelineService = new InMemoryPipelineDefinitionService(pipeline);
        ExecutionStateService stateService = new ExecutionStateServiceImpl(new ObjectMapper());
        NodeScheduler scheduler = new NodeSchedulerImpl();
        NodeExecutorRegistry executorRegistry = new NodeExecutorRegistry(List.of(
            new InputNodeExecutor(),
            new OutputNodeExecutor()
        ));
        EventPublisher eventPublisher = (eventType, payload) -> {
            // no-op for unit test
        };

        AgentExecutionService service = new AgentExecutionServiceImpl(
            pipelineService,
            stateService,
            scheduler,
            executorRegistry,
            eventPublisher,
            new ExecutionMetrics(new SimpleMeterRegistry())
        );

        StartExecutionCommand command = new StartExecutionCommand(
            UUID.randomUUID(),
            pipeline.agentId(),
            pipeline.id(),
            Map.of("text", "hello"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        // When
        AgentExecutionResult result = service.executeSync(command, Duration.ofSeconds(5));

        // Then
        assertNotNull(result.executionId());
        assertEquals(ExecutionStatus.COMPLETED, result.status());
        assertNotNull(result.output());
    }

    @Test
    void shouldFailWhenOutputSourceIsMissing() {
        PipelineDefinition pipeline = createFailingPipeline();
        PipelineDefinitionService pipelineService = new InMemoryPipelineDefinitionService(pipeline);
        ExecutionStateService stateService = new ExecutionStateServiceImpl(new ObjectMapper());
        NodeScheduler scheduler = new NodeSchedulerImpl();
        NodeExecutorRegistry executorRegistry = new NodeExecutorRegistry(List.of(
            new InputNodeExecutor(),
            new OutputNodeExecutor()
        ));
        EventPublisher eventPublisher = (eventType, payload) -> {
        };

        AgentExecutionService service = new AgentExecutionServiceImpl(
            pipelineService,
            stateService,
            scheduler,
            executorRegistry,
            eventPublisher,
            new ExecutionMetrics(new SimpleMeterRegistry())
        );

        StartExecutionCommand command = new StartExecutionCommand(
            UUID.randomUUID(),
            pipeline.agentId(),
            pipeline.id(),
            Map.of("text", "hello"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = service.executeSync(command, Duration.ofSeconds(5));
        assertEquals(ExecutionStatus.FAILED, result.status());
    }

    @Test
    void shouldExecuteTransformPipelineSync() {
        PipelineDefinition pipeline = createTransformPipeline();
        PipelineDefinitionService pipelineService = new InMemoryPipelineDefinitionService(pipeline);
        ExecutionStateService stateService = new ExecutionStateServiceImpl(new ObjectMapper());
        NodeScheduler scheduler = new NodeSchedulerImpl();
        NodeExecutorRegistry executorRegistry = new NodeExecutorRegistry(List.of(
            new InputNodeExecutor(),
            new TransformNodeExecutor(),
            new OutputNodeExecutor()
        ));
        EventPublisher eventPublisher = (eventType, payload) -> {
        };

        AgentExecutionService service = new AgentExecutionServiceImpl(
            pipelineService,
            stateService,
            scheduler,
            executorRegistry,
            eventPublisher,
            new ExecutionMetrics(new SimpleMeterRegistry())
        );

        StartExecutionCommand command = new StartExecutionCommand(
            UUID.randomUUID(),
            pipeline.agentId(),
            pipeline.id(),
            Map.of("text", "hello"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = service.executeSync(command, Duration.ofSeconds(5));
        assertEquals(ExecutionStatus.COMPLETED, result.status());
    }

    private PipelineDefinition createSimplePipeline() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        PipelineNode input = new PipelineNode(
            "input",
            NodeType.INPUT,
            "Input",
            Map.of(),
            new PipelineNode.Position(100, 100)
        );

        PipelineNode output = new PipelineNode(
            "output",
            NodeType.OUTPUT,
            "Output",
            Map.of("source", "input", "sourceKey", "text"),
            new PipelineNode.Position(300, 100)
        );

        PipelineEdge edge = new PipelineEdge("e1", "input", "output", "output", "input", null);

        return new PipelineDefinition(
            id,
            agentId,
            "Simple Pipeline",
            1,
            "input",
            List.of(input, output),
            List.of(edge)
        );
    }

    private PipelineDefinition createFailingPipeline() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        PipelineNode input = new PipelineNode(
            "input",
            NodeType.INPUT,
            "Input",
            Map.of(),
            new PipelineNode.Position(100, 100)
        );

        PipelineNode output = new PipelineNode(
            "output",
            NodeType.OUTPUT,
            "Output",
            Map.of("source", "missing-node", "sourceKey", "text"),
            new PipelineNode.Position(300, 100)
        );

        PipelineEdge edge = new PipelineEdge("e1", "input", "output", "output", "input", null);

        return new PipelineDefinition(
            id,
            agentId,
            "Failing Pipeline",
            1,
            "input",
            List.of(input, output),
            List.of(edge)
        );
    }

    private PipelineDefinition createTransformPipeline() {
        UUID id = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        PipelineNode input = new PipelineNode(
            "input",
            NodeType.INPUT,
            "Input",
            Map.of(),
            new PipelineNode.Position(100, 100)
        );

        PipelineNode transform = new PipelineNode(
            "transform",
            NodeType.TRANSFORM,
            "Transform",
            Map.of("type", "jsonpath", "source", "input", "sourceKey", "output", "jsonPath", "$.text", "outputKey", "textOut"),
            new PipelineNode.Position(220, 100)
        );

        PipelineNode output = new PipelineNode(
            "output",
            NodeType.OUTPUT,
            "Output",
            Map.of("source", "transform", "sourceKey", "textOut"),
            new PipelineNode.Position(340, 100)
        );

        return new PipelineDefinition(
            id,
            agentId,
            "Transform Pipeline",
            1,
            "input",
            List.of(input, transform, output),
            List.of(
                new PipelineEdge("e1", "input", "transform", "output", "input", null),
                new PipelineEdge("e2", "transform", "output", "output", "input", null)
            )
        );
    }

    private static final class InMemoryPipelineDefinitionService implements PipelineDefinitionService {
        private final PipelineDefinition pipeline;

        private InMemoryPipelineDefinitionService(PipelineDefinition pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        public PipelineDefinition loadPipelineDefinition(UUID pipelineId, UUID tenantId) {
            return pipeline;
        }

        @Override
        public PipelineDefinition getActivePipeline(UUID agentId, UUID tenantId) {
            return pipeline;
        }

        @Override
        public ValidationResult validatePipeline(PipelineDefinition pipeline) {
            return ValidationResult.success();
        }

        @Override
        public void invalidateCache(UUID pipelineId) {
        }

        @Override
        public void clearCache() {
        }
    }
}

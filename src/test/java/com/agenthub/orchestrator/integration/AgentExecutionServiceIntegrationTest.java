package com.agenthub.orchestrator.integration;

import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import com.agenthub.orchestrator.domain.execution.NodeResult;
import com.agenthub.orchestrator.dto.AgentExecutionResult;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.entity.AgentVersionEntity;
import com.agenthub.orchestrator.OrchestratorApplication;
import com.agenthub.orchestrator.repository.AgentVersionRepository;
import com.agenthub.orchestrator.service.agent.AgentExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for agent execution flow with PostgreSQL Testcontainer.
 *
 * @since 1.0.0
 */
@SpringBootTest(classes = OrchestratorApplication.class, properties = "spring.classformat.ignore=true")
@Testcontainers(disabledWithoutDocker = true)
class AgentExecutionServiceIntegrationTest {

    private static final AtomicReference<HttpServer> MOCK_SERVER = new AtomicReference<>();
    private static final AtomicReference<String> MOCK_SERVER_BASE_URL = new AtomicReference<>();
    private static final AtomicReference<RecordedRequest> LAST_ECHO_REQUEST = new AtomicReference<>();
    private static final AtomicReference<LlmResponseMode> LLM_RESPONSE_MODE = new AtomicReference<>(LlmResponseMode.NORMAL);
    private static final AtomicInteger LLM_GENERATE_CALL_COUNT = new AtomicInteger(0);
    private static final AtomicInteger HTTP_FLAKY_5XX_CALL_COUNT = new AtomicInteger(0);
    private static final AtomicInteger HTTP_4XX_CALL_COUNT = new AtomicInteger(0);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("agenthub")
        .withUsername("agenthub")
        .withPassword("agenthub_dev");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        ensureMockServerStarted();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces", () -> "true");
        registry.add("agenthub.orchestrator.events.rabbitmq-enabled", () -> "false");
        registry.add("agenthub.backend.base-url", MOCK_SERVER_BASE_URL::get);
        registry.add("agenthub.backend.max-retries", () -> "0");
        registry.add("agenthub.backend.request-timeout-ms", () -> "1000");
    }

    @Autowired
    private AgentExecutionService agentExecutionService;

    @Autowired
    private AgentVersionRepository agentVersionRepository;

    @BeforeEach
    void setUp() {
        LAST_ECHO_REQUEST.set(null);
        LLM_RESPONSE_MODE.set(LlmResponseMode.NORMAL);
        LLM_GENERATE_CALL_COUNT.set(0);
        HTTP_FLAKY_5XX_CALL_COUNT.set(0);
        HTTP_4XX_CALL_COUNT.set(0);
    }

    @AfterAll
    static void tearDown() {
        HttpServer server = MOCK_SERVER.getAndSet(null);
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldExecuteSimplePipelineFromDatabase() {
        // Given
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(simplePipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "hello integration"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        // When
        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(10));

        // Then
        assertNotNull(result.executionId());
        assertEquals(ExecutionStatus.COMPLETED, result.status());
    }

    @Test
    void shouldExecutePipelineWithLlmNodeUsingMockedProviderEndpoints() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(llmPipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "hello llm integration"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(10));

        assertEquals(ExecutionStatus.COMPLETED, result.status());
        @SuppressWarnings("unchecked")
        Map<String, NodeResult> nodeResults = (Map<String, NodeResult>) result.output().get("nodeResults");
        NodeResult llmNodeResult = nodeResults.get("llm");
        assertNotNull(llmNodeResult);
        assertEquals("mocked llm response", llmNodeResult.getDataAsMap().get("answer"));
    }

    @Test
    void shouldRetryLlmNodeOnTransientFailureAndCompleteExecution() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        LLM_RESPONSE_MODE.set(LlmResponseMode.FAIL_TWICE_THEN_SUCCESS);

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(llmPipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "hello llm retry"),
            StartExecutionCommand.ExecutionMode.SYNC,
            15000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(15));

        assertEquals(ExecutionStatus.COMPLETED, result.status());
        assertEquals(3, LLM_GENERATE_CALL_COUNT.get());
    }

    @Test
    void shouldFailLlmNodeOnTimeout() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        LLM_RESPONSE_MODE.set(LlmResponseMode.SLOW_TIMEOUT);

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(llmPipelineJson(agentId, 150));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "hello llm timeout"),
            StartExecutionCommand.ExecutionMode.SYNC,
            15000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(15));

        assertEquals(ExecutionStatus.FAILED, result.status());
    }

    @Test
    void shouldExecutePipelineWithHttpNodeAndTemplateHeadersAndBody() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(httpPipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "hello http", "traceId", "trace-123"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(10));

        assertEquals(ExecutionStatus.COMPLETED, result.status());
        RecordedRequest request = LAST_ECHO_REQUEST.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("trace-123", request.header("X-Trace-Id"));
        assertEquals("{\"message\":\"hello http\"}", request.body());
    }

    @Test
    void shouldRetryHttpNodeOn5xxAndThenComplete() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(httpRetry5xxPipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "retry-5xx"),
            StartExecutionCommand.ExecutionMode.SYNC,
            15000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(15));

        assertEquals(ExecutionStatus.COMPLETED, result.status());
        assertEquals(3, HTTP_FLAKY_5XX_CALL_COUNT.get());
    }

    @Test
    void shouldNotRetryHttpNodeOn4xxAndFailOnce() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(httpNonRetryable4xxPipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "retry-4xx"),
            StartExecutionCommand.ExecutionMode.SYNC,
            15000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(15));

        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(1, HTTP_4XX_CALL_COUNT.get());
    }

    @Test
    void shouldFailExecutionAndNotRunDownstreamNodesWhenIntermediateNodeFails() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(failingIntermediatePipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "hello failure"),
            StartExecutionCommand.ExecutionMode.SYNC,
            10000L,
            UUID.randomUUID()
        );

        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(10));

        assertEquals(ExecutionStatus.FAILED, result.status());
        @SuppressWarnings("unchecked")
        java.util.Set<String> failedNodes = (java.util.Set<String>) result.output().get("failedNodes");
        @SuppressWarnings("unchecked")
        java.util.Set<String> completedNodes = (java.util.Set<String>) result.output().get("completedNodes");
        assertTrue(failedNodes.contains("transform_fail"));
        assertFalse(completedNodes.contains("output"));
    }

    @Test
    void shouldExecuteParallelBranchesConcurrentlyAndJoinBeforeOutput() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setTenantId(tenantId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(parallelPipelineJson(agentId));
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        agentVersion = agentVersionRepository.save(agentVersion);

        StartExecutionCommand command = new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersion.getId(),
            Map.of("text", "parallel"),
            StartExecutionCommand.ExecutionMode.SYNC,
            20000L,
            UUID.randomUUID()
        );

        long startedAt = System.nanoTime();
        AgentExecutionResult result = agentExecutionService.executeSync(command, Duration.ofSeconds(20));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertEquals(ExecutionStatus.COMPLETED, result.status());
        assertTrue(elapsedMs < 1800, "Parallel branches should finish in under 1800ms, actual=" + elapsedMs + "ms");
        @SuppressWarnings("unchecked")
        Map<String, NodeResult> nodeResults = (Map<String, NodeResult>) result.output().get("nodeResults");
        assertEquals("A|B", nodeResults.get("join").getDataAsMap().get("merged"));
    }

    private Map<String, Object> simplePipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "Simple Integration Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of(
                    "id", "input",
                    "type", "INPUT",
                    "name", "Input",
                    "config", Map.of(),
                    "position", Map.of("x", 100, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "input", "sourceKey", "text"),
                    "position", Map.of("x", 300, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of(
                    "id", "e1",
                    "sourceNodeId", "input",
                    "targetNodeId", "output",
                    "sourcePort", "output",
                    "targetPort", "input"
                )
            )
        );
    }

    private Map<String, Object> llmPipelineJson(UUID agentId) {
        return llmPipelineJson(agentId, null);
    }

    private Map<String, Object> llmPipelineJson(UUID agentId, Integer timeoutMs) {
        Map<String, Object> llmConfig = timeoutMs == null
            ? Map.of(
                "provider", "ollama",
                "model", "llama3.2:3b",
                "prompt", "Summarize: {{text}}",
                "outputKey", "answer"
            )
            : Map.of(
                "provider", "ollama",
                "model", "llama3.2:3b",
                "prompt", "Summarize: {{text}}",
                "outputKey", "answer",
                "timeoutMs", timeoutMs
            );

        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "LLM Integration Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of(
                    "id", "input",
                    "type", "INPUT",
                    "name", "Input",
                    "config", Map.of(),
                    "position", Map.of("x", 100, "y", 100)
                ),
                Map.of(
                    "id", "llm",
                    "type", "LLM",
                    "name", "LLM",
                    "config", llmConfig,
                    "position", Map.of("x", 250, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "llm", "sourceKey", "answer"),
                    "position", Map.of("x", 400, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of("id", "e1", "sourceNodeId", "input", "targetNodeId", "llm", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e2", "sourceNodeId", "llm", "targetNodeId", "output", "sourcePort", "output", "targetPort", "input")
            )
        );
    }

    private Map<String, Object> httpRetry5xxPipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "HTTP Retry 5xx Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of("id", "input", "type", "INPUT", "name", "Input", "config", Map.of(), "position", Map.of("x", 100, "y", 100)),
                Map.of(
                    "id", "http",
                    "type", "HTTP",
                    "name", "Http",
                    "config", Map.of(
                        "method", "GET",
                        "url", MOCK_SERVER_BASE_URL.get() + "/http/flaky-5xx"
                    ),
                    "position", Map.of("x", 260, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "http", "sourceKey", "response"),
                    "position", Map.of("x", 420, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of("id", "e1", "sourceNodeId", "input", "targetNodeId", "http", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e2", "sourceNodeId", "http", "targetNodeId", "output", "sourcePort", "output", "targetPort", "input")
            )
        );
    }

    private Map<String, Object> httpNonRetryable4xxPipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "HTTP Non Retryable 4xx Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of("id", "input", "type", "INPUT", "name", "Input", "config", Map.of(), "position", Map.of("x", 100, "y", 100)),
                Map.of(
                    "id", "http",
                    "type", "HTTP",
                    "name", "Http",
                    "config", Map.of(
                        "method", "GET",
                        "url", MOCK_SERVER_BASE_URL.get() + "/http/client-4xx"
                    ),
                    "position", Map.of("x", 260, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "http", "sourceKey", "response"),
                    "position", Map.of("x", 420, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of("id", "e1", "sourceNodeId", "input", "targetNodeId", "http", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e2", "sourceNodeId", "http", "targetNodeId", "output", "sourcePort", "output", "targetPort", "input")
            )
        );
    }

    private Map<String, Object> httpPipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "HTTP Integration Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of(
                    "id", "input",
                    "type", "INPUT",
                    "name", "Input",
                    "config", Map.of(),
                    "position", Map.of("x", 100, "y", 100)
                ),
                Map.of(
                    "id", "http",
                    "type", "HTTP",
                    "name", "Http",
                    "config", Map.of(
                        "method", "POST",
                        "url", MOCK_SERVER_BASE_URL.get() + "/http/echo",
                        "headers", Map.of("X-Trace-Id", "{{traceId}}"),
                        "body", "{\"message\":\"{{text}}\"}",
                        "outputKey", "payload"
                    ),
                    "position", Map.of("x", 250, "y", 100)
                ),
                Map.of(
                    "id", "transform",
                    "type", "TRANSFORM",
                    "name", "Transform",
                    "config", Map.of(
                        "type", "jq",
                        "source", "http",
                        "sourceKey", "payload",
                        "query", ".",
                        "outputKey", "httpBody"
                    ),
                    "position", Map.of("x", 400, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "transform", "sourceKey", "httpBody"),
                    "position", Map.of("x", 550, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of("id", "e1", "sourceNodeId", "input", "targetNodeId", "http", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e2", "sourceNodeId", "http", "targetNodeId", "transform", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e3", "sourceNodeId", "transform", "targetNodeId", "output", "sourcePort", "output", "targetPort", "input")
            )
        );
    }

    private Map<String, Object> failingIntermediatePipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "Failing Intermediate Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of(
                    "id", "input",
                    "type", "INPUT",
                    "name", "Input",
                    "config", Map.of(),
                    "position", Map.of("x", 100, "y", 100)
                ),
                Map.of(
                    "id", "transform_fail",
                    "type", "TRANSFORM",
                    "name", "Transform Fail",
                    "config", Map.of(
                        "type", "jsonpath",
                        "inputKey", "missingKey",
                        "jsonPath", "$.value",
                        "outputKey", "result"
                    ),
                    "position", Map.of("x", 250, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "transform_fail", "sourceKey", "result"),
                    "position", Map.of("x", 400, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of("id", "e1", "sourceNodeId", "input", "targetNodeId", "transform_fail", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e2", "sourceNodeId", "transform_fail", "targetNodeId", "output", "sourcePort", "output", "targetPort", "input")
            )
        );
    }

    private Map<String, Object> parallelPipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "Parallel Pipeline",
            "version", 1,
            "entryNodeId", "input",
            "nodes", List.of(
                Map.of(
                    "id", "input",
                    "type", "INPUT",
                    "name", "Input",
                    "config", Map.of(),
                    "position", Map.of("x", 100, "y", 100)
                ),
                Map.of(
                    "id", "http_a",
                    "type", "HTTP",
                    "name", "Http A",
                    "config", Map.of(
                        "method", "GET",
                        "url", MOCK_SERVER_BASE_URL.get() + "/parallel/a"
                    ),
                    "position", Map.of("x", 250, "y", 50)
                ),
                Map.of(
                    "id", "http_b",
                    "type", "HTTP",
                    "name", "Http B",
                    "config", Map.of(
                        "method", "GET",
                        "url", MOCK_SERVER_BASE_URL.get() + "/parallel/b"
                    ),
                    "position", Map.of("x", 250, "y", 150)
                ),
                Map.of(
                    "id", "join",
                    "type", "TRANSFORM",
                    "name", "Join",
                    "config", Map.of(
                        "type", "template",
                        "template", "{{node.http_a.response}}|{{node.http_b.response}}",
                        "outputKey", "merged"
                    ),
                    "position", Map.of("x", 420, "y", 100)
                ),
                Map.of(
                    "id", "output",
                    "type", "OUTPUT",
                    "name", "Output",
                    "config", Map.of("source", "join", "sourceKey", "merged"),
                    "position", Map.of("x", 560, "y", 100)
                )
            ),
            "edges", List.of(
                Map.of("id", "e1", "sourceNodeId", "input", "targetNodeId", "http_a", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e2", "sourceNodeId", "input", "targetNodeId", "http_b", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e3", "sourceNodeId", "http_a", "targetNodeId", "join", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e4", "sourceNodeId", "http_b", "targetNodeId", "join", "sourcePort", "output", "targetPort", "input"),
                Map.of("id", "e5", "sourceNodeId", "join", "targetNodeId", "output", "sourcePort", "output", "targetPort", "input")
            )
        );
    }

    private static void ensureMockServerStarted() {
        if (MOCK_SERVER.get() != null) {
            return;
        }
        synchronized (MOCK_SERVER) {
            if (MOCK_SERVER.get() != null) {
                return;
            }
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(Executors.newCachedThreadPool());

                server.createContext("/api/settings", exchange -> {
                    String payload = """
                        {
                          "openai": {"apiKey":"test-openai", "model":"gpt-4o-mini", "models":[], "baseUrl":"https://api.openai.com", "organizationId":null, "temperature":0.7, "topP":1.0, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":512},
                          "ollama": {"apiKey":"", "model":"llama3.2:3b", "models":[], "baseUrl":"%s", "organizationId":null, "temperature":0.2, "topP":0.9, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":256},
                          "claude": {"apiKey":"test-claude", "model":"claude-3-5-sonnet", "models":[], "baseUrl":"https://api.anthropic.com", "organizationId":null, "temperature":0.7, "topP":1.0, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":512},
                          "generic": {"apiKey":"test-generic", "model":"gpt-4o-mini", "models":[], "baseUrl":"https://example.com", "organizationId":null, "temperature":0.7, "topP":1.0, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":512},
                          "general": {"language":"pt-BR", "defaultProvider":"ollama"}
                        }
                        """.formatted(MOCK_SERVER_BASE_URL.get());
                    writeJson(exchange, 200, payload);
                });

                server.createContext("/api/generate", exchange -> {
                    int callCount = LLM_GENERATE_CALL_COUNT.incrementAndGet();
                    LlmResponseMode mode = LLM_RESPONSE_MODE.get();
                    if (mode == LlmResponseMode.FAIL_TWICE_THEN_SUCCESS && callCount <= 2) {
                        writeJson(exchange, 500, "{\"error\":\"transient failure\"}");
                        return;
                    }
                    if (mode == LlmResponseMode.SLOW_TIMEOUT) {
                        sleepMillis(1000);
                    }

                    writeJson(exchange, 200, "{\"model\":\"llama3.2:3b\",\"response\":\"mocked llm response\"}");
                });

                server.createContext("/http/echo", exchange -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    LAST_ECHO_REQUEST.set(new RecordedRequest(exchange.getRequestMethod(), flattenHeaders(exchange.getRequestHeaders()), body));
                    writeJson(exchange, 200, "{\"ok\":true}");
                });

                server.createContext("/http/flaky-5xx", exchange -> {
                    int callCount = HTTP_FLAKY_5XX_CALL_COUNT.incrementAndGet();
                    if (callCount <= 2) {
                        writeJson(exchange, 500, "{\"error\":\"transient\"}");
                        return;
                    }
                    writeJson(exchange, 200, "{\"ok\":true}");
                });

                server.createContext("/http/client-4xx", exchange -> {
                    HTTP_4XX_CALL_COUNT.incrementAndGet();
                    writeJson(exchange, 400, "{\"error\":\"bad request\"}");
                });

                server.createContext("/parallel/a", exchange -> {
                    sleepMillis(1000);
                    writeText(exchange, 200, "A");
                });

                server.createContext("/parallel/b", exchange -> {
                    sleepMillis(1000);
                    writeText(exchange, 200, "B");
                });

                server.start();
                MOCK_SERVER_BASE_URL.set("http://127.0.0.1:" + server.getAddress().getPort());
                MOCK_SERVER.set(server);
            } catch (IOException e) {
                throw new RuntimeException("Failed to start mock HTTP server", e);
            }
        }
    }

    private static void writeJson(HttpExchange exchange, int status, String payload) throws IOException {
        writeResponse(exchange, status, "application/json", payload);
    }

    private static void writeText(HttpExchange exchange, int status, String payload) throws IOException {
        writeResponse(exchange, status, "text/plain", payload);
    }

    private static void writeResponse(HttpExchange exchange, int status, String contentType, String payload) throws IOException {
        byte[] responseBytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
        exchange.close();
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> flattenHeaders(com.sun.net.httpserver.Headers headers) {
        java.util.Map<String, String> flattened = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            List<String> values = entry.getValue();
            flattened.put(entry.getKey(), values == null || values.isEmpty() ? null : values.get(0));
        }
        return flattened;
    }

    record RecordedRequest(String method, Map<String, String> headers, String body) {
        String header(String key) {
            String value = headers.get(key);
            if (value != null) {
                return value;
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    enum LlmResponseMode {
        NORMAL,
        FAIL_TWICE_THEN_SUCCESS,
        SLOW_TIMEOUT
    }
}

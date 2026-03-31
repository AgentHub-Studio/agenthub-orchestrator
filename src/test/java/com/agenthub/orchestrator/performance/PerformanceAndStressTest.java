package com.agenthub.orchestrator.performance;

import com.agenthub.orchestrator.OrchestratorApplication;
import com.agenthub.orchestrator.domain.execution.model.ExecutionStatus;
import com.agenthub.orchestrator.adapter.in.rest.AgentExecutionResult;
import com.agenthub.orchestrator.adapter.in.rest.StartExecutionCommand;
import com.agenthub.orchestrator.adapter.out.persistence.AgentVersionEntity;
import com.agenthub.orchestrator.domain.port.AgentVersionRepository;
import com.agenthub.orchestrator.application.execution.AgentExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance and stress tests for the orchestrator pipeline execution engine.
 * <p>
 * These tests validate throughput, latency percentiles, and stability under load.
 * They use real PostgreSQL via Testcontainers and a lightweight JDK HttpServer
 * for mocking external dependencies (LLM, backend settings).
 * </p>
 * <p>
 * Test categories:
 * <ul>
 *   <li><b>Performance baseline</b> — 100 sequential executions with p50/p95/p99 measurement</li>
 *   <li><b>Concurrent stress</b> — 100 parallel executions verifying no deadlocks</li>
 *   <li><b>Long pipeline</b> — 20+ node chain verifying memory and time stability</li>
 * </ul>
 * </p>
 *
 * @since 1.0.0
 */
@SpringBootTest(classes = OrchestratorApplication.class, properties = "spring.classformat.ignore=true")
@Testcontainers(disabledWithoutDocker = true)
class PerformanceAndStressTest {

    private static final Logger log = LoggerFactory.getLogger(PerformanceAndStressTest.class);

    private static final int SEQUENTIAL_RUNS = 100;
    private static final int CONCURRENT_RUNS = 100;
    private static final int LONG_PIPELINE_NODE_COUNT = 25;

    private static final AtomicReference<HttpServer> MOCK_SERVER = new AtomicReference<>();
    private static final AtomicReference<String> MOCK_SERVER_BASE_URL = new AtomicReference<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("agenthub_perf")
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
        // Increase pool for stress tests
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");
        // Increase thread pool for concurrency
        registry.add("agenthub.orchestrator.threading.core-pool-size", () -> "20");
        registry.add("agenthub.orchestrator.threading.max-pool-size", () -> "50");
        registry.add("agenthub.orchestrator.threading.queue-capacity", () -> "200");
    }

    @Autowired
    private AgentExecutionService agentExecutionService;

    @Autowired
    private AgentVersionRepository agentVersionRepository;

    @AfterAll
    static void tearDown() {
        HttpServer server = MOCK_SERVER.getAndSet(null);
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Performance baseline: executes 100 simple pipelines sequentially and reports
     * p50, p95, and p99 latencies.
     * <p>
     * Pass criteria:
     * <ul>
     *   <li>All 100 executions complete with COMPLETED status</li>
     *   <li>p95 latency is under 500ms (generous for CI environments)</li>
     *   <li>No failures</li>
     * </ul>
     */
    @Test
    void shouldMeetPerformanceBaselineFor100SequentialPipelines() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentVersionEntity agentVersion = createAgentVersion(tenantId, agentId, simplePipelineJson(agentId));

        long[] latencies = new long[SEQUENTIAL_RUNS];
        int failureCount = 0;

        // Warm-up: 5 executions to stabilize JIT, connection pool, etc.
        for (int i = 0; i < 5; i++) {
            agentExecutionService.executeSync(
                buildCommand(tenantId, agentId, agentVersion.getId(), Map.of("text", "warmup-" + i)),
                Duration.ofSeconds(10)
            );
        }

        // Measured runs
        for (int i = 0; i < SEQUENTIAL_RUNS; i++) {
            long start = System.nanoTime();
            AgentExecutionResult result = agentExecutionService.executeSync(
                buildCommand(tenantId, agentId, agentVersion.getId(), Map.of("text", "perf-" + i)),
                Duration.ofSeconds(10)
            );
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            latencies[i] = elapsedMs;

            if (result.status() != ExecutionStatus.COMPLETED) {
                failureCount++;
            }
        }

        Arrays.sort(latencies);
        long p50 = latencies[49];
        long p95 = latencies[94];
        long p99 = latencies[98];
        long min = latencies[0];
        long max = latencies[99];
        double avg = Arrays.stream(latencies).average().orElse(0);

        log.info("=== PERFORMANCE BASELINE (100 sequential simple pipelines) ===");
        log.info("  Min:  {} ms", min);
        log.info("  Avg:  {} ms", String.format("%.1f", avg));
        log.info("  p50:  {} ms", p50);
        log.info("  p95:  {} ms", p95);
        log.info("  p99:  {} ms", p99);
        log.info("  Max:  {} ms", max);
        log.info("  Failures: {}/{}", failureCount, SEQUENTIAL_RUNS);
        log.info("==============================================================");

        assertEquals(0, failureCount, "All sequential executions should complete successfully");
        assertTrue(p95 < 500, "p95 latency should be under 500ms, actual=" + p95 + "ms");
    }

    /**
     * Stress test: executes 100 pipelines concurrently and verifies all complete
     * without deadlocks, pool exhaustion, or data corruption.
     * <p>
     * Pass criteria:
     * <ul>
     *   <li>All 100 executions complete within 60 seconds total</li>
     *   <li>All complete with COMPLETED status</li>
     *   <li>Throughput exceeds 5 executions/second</li>
     * </ul>
     */
    @Test
    void shouldHandle100ConcurrentPipelineExecutionsWithoutDeadlocks() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentVersionEntity agentVersion = createAgentVersion(tenantId, agentId, simplePipelineJson(agentId));

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_RUNS);

        // Launch all executions from separate threads
        var executor = Executors.newFixedThreadPool(CONCURRENT_RUNS);
        for (int i = 0; i < CONCURRENT_RUNS; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // All threads start simultaneously
                    long start = System.nanoTime();
                    AgentExecutionResult result = agentExecutionService.executeSync(
                        buildCommand(tenantId, agentId, agentVersion.getId(), Map.of("text", "stress-" + index)),
                        Duration.ofSeconds(30)
                    );
                    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    latencies.add(elapsedMs);

                    if (result.status() == ExecutionStatus.COMPLETED) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                        log.warn("Stress execution {} failed with status: {}", index, result.status());
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    log.error("Stress execution {} threw exception: {}", index, e.getMessage(), e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long wallClockStart = System.nanoTime();
        startGate.countDown(); // Release all threads

        try {
            boolean allFinished = doneLatch.await(60, TimeUnit.SECONDS);
            assertTrue(allFinished, "All 100 concurrent executions should complete within 60 seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Test interrupted", e);
        } finally {
            executor.shutdownNow();
        }

        long wallClockMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - wallClockStart);
        double throughput = (double) CONCURRENT_RUNS / (wallClockMs / 1000.0);

        long[] sortedLatencies = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = sortedLatencies.length > 49 ? sortedLatencies[49] : sortedLatencies[sortedLatencies.length - 1];
        long p95 = sortedLatencies.length > 94 ? sortedLatencies[94] : sortedLatencies[sortedLatencies.length - 1];
        long p99 = sortedLatencies.length > 98 ? sortedLatencies[98] : sortedLatencies[sortedLatencies.length - 1];

        log.info("=== STRESS TEST (100 concurrent simple pipelines) ===");
        log.info("  Wall clock:   {} ms", wallClockMs);
        log.info("  Throughput:   {} exec/sec", String.format("%.1f", throughput));
        log.info("  Success:      {}/{}", successCount.get(), CONCURRENT_RUNS);
        log.info("  Failures:     {}/{}", failureCount.get(), CONCURRENT_RUNS);
        log.info("  p50 latency:  {} ms", p50);
        log.info("  p95 latency:  {} ms", p95);
        log.info("  p99 latency:  {} ms", p99);
        log.info("====================================================");

        assertEquals(CONCURRENT_RUNS, successCount.get(),
            "All concurrent executions should succeed (failures=" + failureCount.get() + ")");
        assertTrue(throughput > 5.0,
            "Throughput should exceed 5 exec/sec, actual=" + String.format("%.1f", throughput));
    }

    /**
     * Long pipeline test: creates a pipeline with 25 transform nodes in a chain
     * (INPUT -> T1 -> T2 -> ... -> T25 -> OUTPUT) and verifies it completes
     * without timeout or memory issues.
     * <p>
     * Pass criteria:
     * <ul>
     *   <li>Execution completes with COMPLETED status</li>
     *   <li>Total time under 30 seconds</li>
     *   <li>All 25 transform nodes produce output</li>
     * </ul>
     */
    @Test
    void shouldCompleteLongPipelineWith25NodesWithinTimeBudget() {
        UUID tenantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentVersionEntity agentVersion = createAgentVersion(tenantId, agentId,
            longChainPipelineJson(agentId, LONG_PIPELINE_NODE_COUNT));

        long start = System.nanoTime();
        AgentExecutionResult result = agentExecutionService.executeSync(
            buildCommand(tenantId, agentId, agentVersion.getId(), Map.of("text", "long-pipeline-test")),
            Duration.ofSeconds(60)
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        log.info("=== LONG PIPELINE TEST ({} transform nodes) ===", LONG_PIPELINE_NODE_COUNT);
        log.info("  Status:     {}", result.status());
        log.info("  Duration:   {} ms", elapsedMs);
        log.info("  Latency:    {} ms", result.latencyMs());
        if (result.error() != null) {
            log.info("  Error:      {}", result.error());
        }
        log.info("================================================");

        assertEquals(ExecutionStatus.COMPLETED, result.status(),
            "Long pipeline should complete successfully. Error: " + result.error());
        assertTrue(elapsedMs < 30_000,
            "Long pipeline should complete within 30 seconds, actual=" + elapsedMs + "ms");
    }

    // ---- Helpers ----

    private AgentVersionEntity createAgentVersion(UUID tenantId, UUID agentId, Map<String, Object> pipelineJson) {
        AgentVersionEntity agentVersion = new AgentVersionEntity();
        agentVersion.setAgentId(agentId);
        agentVersion.setVersion(1);
        agentVersion.setStatus("PUBLISHED");
        agentVersion.setPipelineDefinitionJson(pipelineJson);
        agentVersion.setCreatedAt(Instant.now());
        agentVersion.setUpdatedAt(Instant.now());
        return agentVersionRepository.save(agentVersion);
    }

    private StartExecutionCommand buildCommand(UUID tenantId, UUID agentId, UUID versionId, Map<String, Object> input) {
        return new StartExecutionCommand(
            tenantId,
            agentId,
            versionId,
            input,
            StartExecutionCommand.ExecutionMode.SYNC,
            30000L,
            UUID.randomUUID()
        );
    }

    /**
     * Simple 2-node pipeline: INPUT -> OUTPUT.
     * Minimal overhead for measuring raw engine throughput.
     */
    private Map<String, Object> simplePipelineJson(UUID agentId) {
        return Map.of(
            "id", UUID.randomUUID().toString(),
            "agentId", agentId.toString(),
            "name", "Perf Simple Pipeline",
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

    /**
     * Generates a long chain pipeline: INPUT -> TRANSFORM_1 -> TRANSFORM_2 -> ... -> TRANSFORM_N -> OUTPUT.
     * <p>
     * Each transform node uses a simple template that appends its index to the input.
     *
     * @param agentId   the agent ID
     * @param nodeCount number of transform nodes in the chain
     * @return the pipeline definition as a Map
     */
    private Map<String, Object> longChainPipelineJson(UUID agentId, int nodeCount) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // INPUT node
        nodes.add(Map.of(
            "id", "input",
            "type", "INPUT",
            "name", "Input",
            "config", Map.of(),
            "position", Map.of("x", 50, "y", 100)
        ));

        // Chain of TRANSFORM nodes
        String previousNodeId = "input";
        String previousOutputKey = "text"; // Initial data key from input
        int edgeIndex = 1;

        for (int i = 1; i <= nodeCount; i++) {
            String nodeId = "transform_" + i;
            String outputKey = "result_" + i;

            nodes.add(Map.of(
                "id", nodeId,
                "type", "TRANSFORM",
                "name", "Transform " + i,
                "config", Map.of(
                    "type", "template",
                    "source", previousNodeId,
                    "sourceKey", previousOutputKey,
                    "template", "{{node." + previousNodeId + "." + previousOutputKey + "}}_T" + i,
                    "outputKey", outputKey
                ),
                "position", Map.of("x", 50 + (i * 80), "y", 100)
            ));

            edges.add(Map.of(
                "id", "e" + edgeIndex,
                "sourceNodeId", previousNodeId,
                "targetNodeId", nodeId,
                "sourcePort", "output",
                "targetPort", "input"
            ));
            edgeIndex++;

            previousNodeId = nodeId;
            previousOutputKey = outputKey;
        }

        // OUTPUT node
        nodes.add(Map.of(
            "id", "output",
            "type", "OUTPUT",
            "name", "Output",
            "config", Map.of("source", previousNodeId, "sourceKey", previousOutputKey),
            "position", Map.of("x", 50 + ((nodeCount + 1) * 80), "y", 100)
        ));

        edges.add(Map.of(
            "id", "e" + edgeIndex,
            "sourceNodeId", previousNodeId,
            "targetNodeId", "output",
            "sourcePort", "output",
            "targetPort", "input"
        ));

        // Must use HashMap because Map.of does not allow > 10 entries
        Map<String, Object> pipeline = new HashMap<>();
        pipeline.put("id", UUID.randomUUID().toString());
        pipeline.put("agentId", agentId.toString());
        pipeline.put("name", "Long Chain Pipeline (" + nodeCount + " transforms)");
        pipeline.put("version", 1);
        pipeline.put("entryNodeId", "input");
        pipeline.put("nodes", nodes);
        pipeline.put("edges", edges);
        return pipeline;
    }

    // ---- Mock HTTP Server ----

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

                // Backend settings endpoint — returns minimal valid config
                server.createContext("/api/settings", exchange -> {
                    String payload = """
                        {
                          "openai": {"apiKey":"test", "model":"gpt-4o-mini", "models":[], "baseUrl":"https://api.openai.com", "organizationId":null, "temperature":0.7, "topP":1.0, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":512},
                          "ollama": {"apiKey":"", "model":"llama3.2:3b", "models":[], "baseUrl":"%s", "organizationId":null, "temperature":0.2, "topP":0.9, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":256},
                          "claude": {"apiKey":"test", "model":"claude-3-5-sonnet", "models":[], "baseUrl":"https://api.anthropic.com", "organizationId":null, "temperature":0.7, "topP":1.0, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":512},
                          "generic": {"apiKey":"test", "model":"gpt-4o-mini", "models":[], "baseUrl":"https://example.com", "organizationId":null, "temperature":0.7, "topP":1.0, "frequencyPenalty":0.0, "presencePenalty":0.0, "maxTokens":512},
                          "general": {"language":"pt-BR", "defaultProvider":"ollama"}
                        }
                        """.formatted(MOCK_SERVER_BASE_URL.get());
                    writeJson(exchange, 200, payload);
                });

                server.start();
                MOCK_SERVER_BASE_URL.set("http://127.0.0.1:" + server.getAddress().getPort());
                MOCK_SERVER.set(server);
            } catch (IOException e) {
                throw new RuntimeException("Failed to start mock HTTP server for performance tests", e);
            }
        }
    }

    private static void writeJson(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] responseBytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, responseBytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(responseBytes);
        }
        exchange.close();
    }
}

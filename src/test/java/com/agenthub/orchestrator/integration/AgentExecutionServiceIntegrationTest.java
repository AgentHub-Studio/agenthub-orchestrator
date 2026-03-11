package com.agenthub.orchestrator.integration;

import com.agenthub.orchestrator.dto.AgentExecutionResult;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.entity.AgentVersionEntity;
import com.agenthub.orchestrator.repository.AgentVersionRepository;
import com.agenthub.orchestrator.service.agent.AgentExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for agent execution flow with PostgreSQL Testcontainer.
 *
 * @since 1.0.0
 */
@SpringBootTest(properties = "spring.classformat.ignore=true")
@Testcontainers(disabledWithoutDocker = true)
class AgentExecutionServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("agenthub")
        .withUsername("agenthub")
        .withPassword("agenthub_dev");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.create_namespaces", () -> "true");
        registry.add("agenthub.orchestrator.events.rabbitmq-enabled", () -> "false");
    }

    @Autowired
    private AgentExecutionService agentExecutionService;

    @Autowired
    private AgentVersionRepository agentVersionRepository;

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
        assertEquals(com.agenthub.orchestrator.domain.execution.ExecutionStatus.COMPLETED, result.status());
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
}

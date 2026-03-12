package com.agenthub.orchestrator.controller;

import com.agenthub.orchestrator.domain.execution.ExecutionStatus;
import com.agenthub.orchestrator.dto.AgentExecutionResult;
import com.agenthub.orchestrator.dto.AgentExecutionStatus;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.projection.ExecutionSummary;
import com.agenthub.orchestrator.repository.ExecutionRepository;
import com.agenthub.orchestrator.service.agent.AgentExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for ExecutionController without bytecode-based mocking.
 *
 * @since 1.0.0
 */
class ExecutionControllerTest {

    @Test
    void shouldStartExecution() throws Exception {
        AgentExecutionServiceStub service = new AgentExecutionServiceStub();
        UUID executionId = UUID.randomUUID();
        service.startResult = CompletableFuture.completedFuture(executionId);

        ExecutionRepository repository = emptyRepository();
        MockMvc mockMvc = mvc(new ExecutionController(service, repository));

        String payload = """
            {
              "tenantId":"%s",
              "agentId":"%s",
              "input":{"text":"hello"},
              "mode":"ASYNC"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.executionId").value(executionId.toString()))
            .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void shouldRejectInvalidMode() throws Exception {
        AgentExecutionServiceStub service = new AgentExecutionServiceStub();
        service.startException = new IllegalArgumentException("No enum constant");

        MockMvc mockMvc = mvc(new ExecutionController(service, emptyRepository()));

        String payload = """
            {
              "tenantId":"%s",
              "agentId":"%s",
              "mode":"invalid"
            }
            """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post("/v1/executions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void shouldGetExecutionStatus() throws Exception {
        AgentExecutionServiceStub service = new AgentExecutionServiceStub();
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        service.statusResult = new AgentExecutionStatus(
            executionId,
            ExecutionStatus.RUNNING,
            42,
            OffsetDateTime.now(),
            null,
            null
        );

        MockMvc mockMvc = mvc(new ExecutionController(service, emptyRepository()));

        mockMvc.perform(get("/v1/executions/{executionId}", executionId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionId").value(executionId.toString()))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.progress").value(42));
    }

    @Test
    void shouldListExecutionsByTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();
        ExecutionSummary summary = new ExecutionSummaryStub(
            UUID.randomUUID(), tenantId, UUID.randomUUID(), UUID.randomUUID(), "COMPLETED"
        );

        ExecutionRepository repository = repositoryWithPage(
            new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 1)
        );
        MockMvc mockMvc = mvc(new ExecutionController(new AgentExecutionServiceStub(), repository));

        mockMvc.perform(get("/v1/executions")
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("COMPLETED"));
    }

    private static final class ExecutionSummaryStub implements ExecutionSummary {
        private final UUID id;
        private final UUID tenantId;
        private final UUID agentId;
        private final UUID agentVersionId;
        private final String status;

        private ExecutionSummaryStub(UUID id, UUID tenantId, UUID agentId, UUID agentVersionId, String status) {
            this.id = id;
            this.tenantId = tenantId;
            this.agentId = agentId;
            this.agentVersionId = agentVersionId;
            this.status = status;
        }

        @Override public UUID getId() { return id; }
        @Override public UUID getTenantId() { return tenantId; }
        @Override public UUID getAgentId() { return agentId; }
        @Override public UUID getAgentVersionId() { return agentVersionId; }
        @Override public String getStatus() { return status; }
        @Override public String getTriggerType() { return "MANUAL"; }
        @Override public java.time.Instant getStartedAt() { return null; }
        @Override public java.time.Instant getFinishedAt() { return null; }
        @Override public java.time.Instant getCreatedAt() { return java.time.Instant.now(); }
    }

    @Test
    void shouldCancelExecution() throws Exception {
        AgentExecutionServiceStub service = new AgentExecutionServiceStub();
        UUID executionId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        MockMvc mockMvc = mvc(new ExecutionController(service, emptyRepository()));

        mockMvc.perform(delete("/v1/executions/{executionId}", executionId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isNoContent());

        if (!executionId.equals(service.cancelExecutionId) || !tenantId.equals(service.cancelTenantId)) {
            throw new AssertionError("cancelExecution was not called with expected values");
        }
    }

    private MockMvc mvc(ExecutionController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private ExecutionRepository emptyRepository() {
        return repositoryWithPage(Page.empty());
    }

    private ExecutionRepository repositoryWithPage(Page<ExecutionSummary> page) {
        return (ExecutionRepository) Proxy.newProxyInstance(
            ExecutionRepository.class.getClassLoader(),
            new Class[]{ExecutionRepository.class},
            (proxy, method, args) -> {
                if ("findSummariesByTenantId".equals(method.getName())) {
                    return page;
                }
                if ("findSummariesByTenantIdAndStatus".equals(method.getName())) {
                    return page;
                }
                if ("toString".equals(method.getName())) {
                    return "ExecutionRepositoryProxy";
                }
                return null;
            }
        );
    }

    private static final class AgentExecutionServiceStub implements AgentExecutionService {
        private CompletableFuture<UUID> startResult = CompletableFuture.completedFuture(UUID.randomUUID());
        private RuntimeException startException;
        private AgentExecutionStatus statusResult = new AgentExecutionStatus(
            UUID.randomUUID(), ExecutionStatus.PENDING, 0, OffsetDateTime.now(), null, null
        );
        private UUID cancelExecutionId;
        private UUID cancelTenantId;

        @Override
        public CompletableFuture<UUID> startExecution(StartExecutionCommand command) {
            if (startException != null) {
                throw startException;
            }
            return startResult;
        }

        @Override
        public AgentExecutionResult executeSync(StartExecutionCommand command, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelExecution(UUID executionId, UUID tenantId) {
            this.cancelExecutionId = executionId;
            this.cancelTenantId = tenantId;
        }

        @Override
        public UUID retryExecution(UUID executionId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentExecutionStatus getExecutionStatus(UUID executionId, UUID tenantId) {
            return statusResult;
        }
    }
}

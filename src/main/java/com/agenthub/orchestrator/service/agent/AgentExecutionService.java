package com.agenthub.orchestrator.service.agent;

import com.agenthub.orchestrator.dto.AgentExecutionResult;
import com.agenthub.orchestrator.dto.AgentExecutionStatus;
import com.agenthub.orchestrator.dto.StartExecutionCommand;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main service for orchestrating agent executions.
 *
 * @since 1.0.0
 */
public interface AgentExecutionService {

    CompletableFuture<UUID> startExecution(StartExecutionCommand command);

    AgentExecutionResult executeSync(StartExecutionCommand command, Duration timeout);

    void cancelExecution(UUID executionId, UUID tenantId);

    UUID retryExecution(UUID executionId, UUID tenantId);

    AgentExecutionStatus getExecutionStatus(UUID executionId, UUID tenantId);
}

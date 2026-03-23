package com.agenthub.orchestrator.application.execution;

import com.agenthub.orchestrator.adapter.in.rest.AgentExecutionResult;
import com.agenthub.orchestrator.adapter.in.rest.AgentExecutionStatus;
import com.agenthub.orchestrator.adapter.in.rest.StartExecutionCommand;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main service for orchestrating agent pipeline executions.
 * <p>
 * This service provides the core orchestration capabilities for executing
 * agent pipelines asynchronously or synchronously. It manages the execution
 * lifecycle, node scheduling, and event publishing.
 * </p>
 *
 * @since 1.0.0
 */
public interface AgentExecutionService {

    /**
     * Starts an asynchronous execution of an agent pipeline.
     * <p>
     * The execution is queued and runs in the background. Use
     * {@link #getExecutionStatus(UUID, UUID)} to track progress.
     * </p>
     *
     * @param command the execution command containing agent and input details
     * @return a future completing with the execution ID once queued
     * @throws IllegalArgumentException if command validation fails
     */
    CompletableFuture<UUID> startExecution(StartExecutionCommand command);

    /**
     * Executes an agent pipeline synchronously with a timeout.
     * <p>
     * Blocks until the execution completes or the timeout is reached.
     * Use this method for synchronous request-response patterns.
     * </p>
     *
     * @param command the execution command
     * @param timeout maximum time to wait for completion
     * @return the execution result including output and status
     * @throws java.util.concurrent.TimeoutException if execution exceeds timeout
     */
    AgentExecutionResult executeSync(StartExecutionCommand command, Duration timeout);

    /**
     * Cancels a running or queued execution.
     * <p>
     * If the execution is already completed or failed, this method has no effect.
     * </p>
     *
     * @param executionId the execution to cancel
     * @param tenantId the tenant owning the execution (for security)
     * @throws com.agenthub.orchestrator.exception.ExecutionNotFoundException if not found
     */
    void cancelExecution(UUID executionId, UUID tenantId);

    /**
     * Retries a failed execution, creating a new execution instance.
     *
     * @param executionId the failed execution to retry
     * @param tenantId the tenant owning the execution
     * @return the ID of the new execution
     * @throws com.agenthub.orchestrator.exception.ExecutionNotFoundException if not found
     */
    UUID retryExecution(UUID executionId, UUID tenantId);

    /**
     * Retrieves the current status and progress of an execution.
     *
     * @param executionId the execution to query
     * @param tenantId the tenant owning the execution (for security)
     * @return the execution status including progress percentage
     * @throws com.agenthub.orchestrator.exception.ExecutionNotFoundException if not found
     */
    AgentExecutionStatus getExecutionStatus(UUID executionId, UUID tenantId);
}

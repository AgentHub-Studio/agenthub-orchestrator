package com.agenthub.orchestrator.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Request payload to start an execution.
 *
 * @since 1.0.0
 */
public record StartExecutionRequest(
    @NotNull
    UUID tenantId,
    @NotNull
    UUID agentId,
    UUID agentVersionId,
    Map<String, Object> input,
    String mode,
    Long timeoutMs,
    UUID triggeredBy
) {
    public StartExecutionCommand toCommand() {
        StartExecutionCommand.ExecutionMode executionMode = mode == null
            ? StartExecutionCommand.ExecutionMode.ASYNC
            : StartExecutionCommand.ExecutionMode.valueOf(mode.toUpperCase());

        return new StartExecutionCommand(
            tenantId,
            agentId,
            agentVersionId,
            input != null ? input : Map.of(),
            executionMode,
            timeoutMs,
            triggeredBy
        );
    }
}

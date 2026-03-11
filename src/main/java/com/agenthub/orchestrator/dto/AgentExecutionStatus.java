package com.agenthub.orchestrator.dto;

import com.agenthub.orchestrator.domain.execution.ExecutionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lightweight execution status DTO.
 *
 * @since 1.0.0
 */
public record AgentExecutionStatus(
    UUID executionId,
    ExecutionStatus status,
    int progress,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    String error
) {}

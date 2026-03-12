package com.agenthub.orchestrator.controller;

import com.agenthub.orchestrator.dto.AgentExecutionStatus;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.dto.StartExecutionRequest;
import com.agenthub.orchestrator.projection.ExecutionSummary;
import com.agenthub.orchestrator.repository.ExecutionRepository;
import com.agenthub.orchestrator.service.agent.AgentExecutionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for execution lifecycle.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/v1/executions")
public class ExecutionController {

    private final AgentExecutionService agentExecutionService;
    private final ExecutionRepository executionRepository;

    public ExecutionController(
        AgentExecutionService agentExecutionService,
        ExecutionRepository executionRepository
    ) {
        this.agentExecutionService = agentExecutionService;
        this.executionRepository = executionRepository;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> startExecution(@Valid @RequestBody StartExecutionRequest request) {
        StartExecutionCommand command = request.toCommand();
        UUID executionId = agentExecutionService.startExecution(command).join();

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "executionId", executionId,
            "status", "QUEUED"
        ));
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<AgentExecutionStatus> getExecutionStatus(
        @PathVariable UUID executionId,
        @RequestParam UUID tenantId
    ) {
        AgentExecutionStatus status = agentExecutionService.getExecutionStatus(executionId, tenantId);
        return ResponseEntity.ok(status);
    }

    @GetMapping
    public ResponseEntity<Page<ExecutionSummary>> listExecutions(
        @RequestParam UUID tenantId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ExecutionSummary> results = status == null
            ? executionRepository.findSummariesByTenantId(tenantId, pageable)
            : executionRepository.findSummariesByTenantIdAndStatus(tenantId, status, pageable);

        return ResponseEntity.ok(results);
    }

    @DeleteMapping("/{executionId}")
    public ResponseEntity<Void> cancelExecution(
        @PathVariable UUID executionId,
        @RequestParam UUID tenantId
    ) {
        agentExecutionService.cancelExecution(executionId, tenantId);
        return ResponseEntity.noContent().build();
    }
}

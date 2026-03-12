package com.agenthub.orchestrator.controller;

import com.agenthub.orchestrator.dto.AgentExecutionStatus;
import com.agenthub.orchestrator.dto.StartExecutionCommand;
import com.agenthub.orchestrator.dto.StartExecutionRequest;
import com.agenthub.orchestrator.projection.ExecutionSummary;
import com.agenthub.orchestrator.repository.ExecutionRepository;
import com.agenthub.orchestrator.service.agent.AgentExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Executions", description = "Agent pipeline execution management")
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
    @Operation(
        summary = "Start agent execution",
        description = "Initiates an asynchronous execution of an agent pipeline"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "Execution queued successfully",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "executionId": "9d3e8679-7425-40de-944b-e07fc1f90ae7",
                      "status": "QUEUED"
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request (missing required fields)",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "error": "validation_error",
                      "message": "Invalid request payload",
                      "details": ["tenantId: must not be null"]
                    }
                    """)
            )
        ),
        @ApiResponse(
            responseCode = "502",
            description = "Backend settings unavailable"
        )
    })
    public ResponseEntity<Map<String, Object>> startExecution(@Valid @RequestBody StartExecutionRequest request) {
        StartExecutionCommand command = request.toCommand();
        UUID executionId = agentExecutionService.startExecution(command).join();

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "executionId", executionId,
            "status", "QUEUED"
        ));
    }

    @GetMapping("/{executionId}")
    @Operation(
        summary = "Get execution status",
        description = "Retrieves the current status and progress of an execution"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Status retrieved successfully"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "Execution not found",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                    {
                      "error": "execution_not_found",
                      "message": "Execution not found: 9d3e8679-7425-40de-944b-e07fc1f90ae7"
                    }
                    """)
            )
        )
    })
    public ResponseEntity<AgentExecutionStatus> getExecutionStatus(
        @Parameter(description = "Execution ID", required = true)
        @PathVariable UUID executionId,
        @Parameter(description = "Tenant ID", required = true)
        @RequestParam UUID tenantId
    ) {
        AgentExecutionStatus status = agentExecutionService.getExecutionStatus(executionId, tenantId);
        return ResponseEntity.ok(status);
    }

    @GetMapping
    @Operation(
        summary = "List executions",
        description = "Retrieves a paginated list of executions for a tenant, optionally filtered by status"
    )
    @ApiResponse(responseCode = "200", description = "Executions retrieved successfully")
    public ResponseEntity<Page<ExecutionSummary>> listExecutions(
        @Parameter(description = "Tenant ID", required = true)
        @RequestParam UUID tenantId,
        @Parameter(description = "Filter by status (optional)", example = "COMPLETED")
        @RequestParam(required = false) String status,
        @Parameter(description = "Page number (0-indexed)", example = "0")
        @RequestParam(defaultValue = "0") int page,
        @Parameter(description = "Page size", example = "20")
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<ExecutionSummary> results = status == null
            ? executionRepository.findSummariesByTenantId(tenantId, pageable)
            : executionRepository.findSummariesByTenantIdAndStatus(tenantId, status, pageable);

        return ResponseEntity.ok(results);
    }

    @DeleteMapping("/{executionId}")
    @Operation(
        summary = "Cancel execution",
        description = "Cancels a running or queued execution"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Execution cancelled successfully"),
        @ApiResponse(responseCode = "404", description = "Execution not found")
    })
    public ResponseEntity<Void> cancelExecution(
        @Parameter(description = "Execution ID", required = true)
        @PathVariable UUID executionId,
        @Parameter(description = "Tenant ID", required = true)
        @RequestParam UUID tenantId
    ) {
        agentExecutionService.cancelExecution(executionId, tenantId);
        return ResponseEntity.noContent().build();
    }
}

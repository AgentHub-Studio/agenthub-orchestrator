package execution

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Service orchestrates agent executions.
type Service struct {
	repo      *Repository
	scheduler *Scheduler
}

// NewService creates an execution Service with the given node registry.
func NewService(pool *pgxpool.Pool, nodeRegistry *NodeRegistry) *Service {
	return &Service{
		repo:      NewRepository(pool),
		scheduler: NewScheduler(nodeRegistry),
	}
}

// StartExecution creates an execution record and runs the pipeline asynchronously.
func (s *Service) StartExecution(ctx context.Context, tenantID string, req CreateExecutionRequest) (AgentExecution, error) {
	inputJSON, _ := json.Marshal(req.Input)

	exec := AgentExecution{
		AgentID:  req.AgentID,
		TenantID: tenantID,
		Status:   StatusPending,
		Input:    string(inputJSON),
	}

	created, err := s.repo.Create(ctx, tenantID, exec)
	if err != nil {
		return AgentExecution{}, fmt.Errorf("service: create execution: %w", err)
	}

	// Run pipeline in background.
	go s.runPipeline(context.Background(), tenantID, created.ID, req.AgentID, req.Input)

	return created, nil
}

// runPipeline loads the agent pipeline and runs it via the DAG scheduler.
func (s *Service) runPipeline(ctx context.Context, tenantID string, executionID, agentID uuid.UUID, input map[string]any) {
	if err := s.repo.UpdateStatus(ctx, tenantID, executionID, StatusRunning, ""); err != nil {
		slog.Error("failed to update execution status to running", "err", err)
		return
	}

	// NOTE: In a full implementation, the pipeline nodes/edges would be loaded from the
	// agenthub-api database. For now, create a minimal single-node pipeline.
	nodes := []*Node{
		{ID: "input", Type: "INPUT", Config: map[string]any{}},
		{ID: "output", Type: "OUTPUT", Config: map[string]any{"sources": []any{"input"}}},
	}
	edges := []Edge{{Source: "input", Target: "output"}}
	dag := NewDAG(nodes, edges)

	pctx := NewPipelineContext(executionID, tenantID, input)

	if err := s.scheduler.Run(ctx, dag, pctx); err != nil {
		slog.Error("pipeline execution failed", "executionId", executionID, "err", err)
		if updateErr := s.repo.UpdateStatus(ctx, tenantID, executionID, StatusFailed, err.Error()); updateErr != nil {
			slog.Error("failed to update execution status to failed", "err", updateErr)
		}
		return
	}

	// Collect final output from "output" node.
	finalOutput, _ := pctx.GetNodeOutput("output")
	outputJSON, _ := json.Marshal(finalOutput)

	if err := s.repo.UpdateStatus(ctx, tenantID, executionID, StatusCompleted, ""); err != nil {
		slog.Error("failed to update execution status to completed", "err", err)
		return
	}

	// Update output field.
	conn, err := s.repo.pool.Acquire(ctx)
	if err == nil {
		defer conn.Release()
		_, _ = conn.Exec(ctx, fmt.Sprintf("SET search_path TO ah_%s, public", tenantID))
		_, _ = conn.Exec(ctx, `UPDATE agent_execution SET output=$1 WHERE id=$2`, string(outputJSON), executionID)
	}

	slog.Info("pipeline execution completed", "executionId", executionID)
}

// GetByID returns an execution by ID.
func (s *Service) GetByID(ctx context.Context, tenantID string, id uuid.UUID) (AgentExecution, error) {
	return s.repo.GetByID(ctx, tenantID, id)
}

// ListByAgent returns paginated executions for an agent.
func (s *Service) ListByAgent(ctx context.Context, tenantID string, agentID uuid.UUID, page, size int) ([]AgentExecution, int, error) {
	offset := page * size
	return s.repo.ListByAgent(ctx, tenantID, agentID, offset, size)
}

// CancelExecution transitions a running execution to cancelled.
func (s *Service) CancelExecution(ctx context.Context, tenantID string, id uuid.UUID) error {
	exec, err := s.repo.GetByID(ctx, tenantID, id)
	if err != nil {
		return err
	}
	if err := Transition(exec.Status, StatusCancelled); err != nil {
		return fmt.Errorf("service: %w", err)
	}
	return s.repo.UpdateStatus(ctx, tenantID, id, StatusCancelled, "")
}

package execution

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/rabbitmq"
)

// EventPublisher publishes execution lifecycle events to a message broker.
// A nil implementation is safe — events are silently dropped when no broker is configured.
type EventPublisher interface {
	Publish(ctx context.Context, event rabbitmq.ExecutionEvent) error
}

// noopPublisher discards all events. Used when RABBITMQ_URL is not set.
type noopPublisher struct{}

func (noopPublisher) Publish(_ context.Context, _ rabbitmq.ExecutionEvent) error { return nil }

// Service orchestrates agent executions.
type Service struct {
	repo      *Repository
	scheduler *Scheduler
	publisher EventPublisher
	apiClient *APIClient
}

// NewService creates an execution Service with the given node registry.
// publisher may be nil — a no-op publisher is used in that case.
func NewService(pool *pgxpool.Pool, nodeRegistry *NodeRegistry, publisher EventPublisher, apiClient *APIClient) *Service {
	if publisher == nil {
		publisher = noopPublisher{}
	}
	return &Service{
		repo:      NewRepository(pool),
		scheduler: NewScheduler(nodeRegistry),
		publisher: publisher,
		apiClient: apiClient,
	}
}

// StartExecution creates an execution record and runs the pipeline asynchronously.
// bearerToken is forwarded to agenthub-api for fetching the agent pipeline definition.
func (s *Service) StartExecution(ctx context.Context, tenantID, bearerToken string, req CreateExecutionRequest) (AgentExecution, error) {
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
	go s.runPipeline(context.Background(), tenantID, bearerToken, created.ID, req.AgentID, req.Input)

	return created, nil
}

// runPipeline loads the agent pipeline and runs it via the DAG scheduler.
func (s *Service) runPipeline(ctx context.Context, tenantID, bearerToken string, executionID, agentID uuid.UUID, input map[string]any) {
	startedAt := time.Now()

	if err := s.repo.UpdateStatus(ctx, tenantID, executionID, StatusRunning, ""); err != nil {
		slog.Error("failed to update execution status to running", "err", err)
		return
	}
	s.publishEvent(ctx, rabbitmq.ExecutionEvent{
		ExecutionID: executionID.String(),
		TenantID:    tenantID,
		AgentID:     agentID.String(),
		Status:      string(StatusRunning),
		StartedAt:   startedAt,
	})

	// Fetch pipeline definition from agenthub-api.
	// Fall back to a trivial INPUT→OUTPUT pipeline if the API is unavailable,
	// so that chat still works in degraded environments.
	var nodes []*Node
	var edges []Edge
	if s.apiClient != nil {
		data, err := s.apiClient.FetchPipeline(ctx, bearerToken, agentID.String())
		if err != nil {
			slog.Warn("failed to fetch pipeline from api, using fallback pipeline", "agentId", agentID, "err", err)
		} else {
			nodes = data.Nodes
			edges = data.Edges
		}
	}
	if len(nodes) == 0 {
		nodes = []*Node{
			{ID: "input", Type: "INPUT", Config: map[string]any{}},
			{ID: "output", Type: "OUTPUT", Config: map[string]any{"sources": []any{"input"}}},
		}
		edges = []Edge{{Source: "input", Target: "output"}}
	}
	dag := NewDAG(nodes, edges)

	pctx := NewPipelineContext(executionID, tenantID, input)
	if bearerToken != "" {
		pctx.SetValue("bearerToken", bearerToken)
	}

	if err := s.scheduler.Run(ctx, dag, pctx); err != nil {
		slog.Error("pipeline execution failed", "executionId", executionID, "err", err)
		if updateErr := s.repo.UpdateStatus(ctx, tenantID, executionID, StatusFailed, err.Error()); updateErr != nil {
			slog.Error("failed to update execution status to failed", "err", updateErr)
		}
		finishedAt := time.Now()
		s.publishEvent(ctx, rabbitmq.ExecutionEvent{
			ExecutionID: executionID.String(),
			TenantID:    tenantID,
			AgentID:     agentID.String(),
			Status:      string(StatusFailed),
			StartedAt:   startedAt,
			FinishedAt:  &finishedAt,
			DurationMs:  time.Since(startedAt).Milliseconds(),
			NodeCount:   len(nodes),
			ErrorMsg:    err.Error(),
		})
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

	finishedAt := time.Now()
	s.publishEvent(ctx, rabbitmq.ExecutionEvent{
		ExecutionID: executionID.String(),
		TenantID:    tenantID,
		AgentID:     agentID.String(),
		Status:      string(StatusCompleted),
		StartedAt:   startedAt,
		FinishedAt:  &finishedAt,
		DurationMs:  time.Since(startedAt).Milliseconds(),
		NodeCount:   len(nodes),
	})

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
	if err := s.repo.UpdateStatus(ctx, tenantID, id, StatusCancelled, ""); err != nil {
		return err
	}
	now := time.Now()
	s.publishEvent(ctx, rabbitmq.ExecutionEvent{
		ExecutionID: id.String(),
		TenantID:    tenantID,
		AgentID:     exec.AgentID.String(),
		Status:      string(StatusCancelled),
		StartedAt:   exec.StartedAt,
		FinishedAt:  &now,
	})
	return nil
}

// publishEvent fires an event without blocking the caller.
// Log-only on failure — event loss is acceptable over slowing the pipeline.
func (s *Service) publishEvent(ctx context.Context, event rabbitmq.ExecutionEvent) {
	if err := s.publisher.Publish(ctx, event); err != nil {
		slog.Warn("failed to publish execution event", "executionId", event.ExecutionID, "err", err)
	}
}

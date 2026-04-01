package execution

import (
	"time"

	"github.com/google/uuid"
)

// ExecutionStatus represents the lifecycle state of an execution.
type ExecutionStatus string

const (
	StatusPending   ExecutionStatus = "PENDING"
	StatusRunning   ExecutionStatus = "RUNNING"
	StatusCompleted ExecutionStatus = "COMPLETED"
	StatusFailed    ExecutionStatus = "FAILED"
	StatusCancelled ExecutionStatus = "CANCELLED"
)

// AgentExecution is the root execution record.
type AgentExecution struct {
	ID        uuid.UUID       `db:"id"`
	AgentID   uuid.UUID       `db:"agent_id"`
	TenantID  string          `db:"tenant_id"`
	Status    ExecutionStatus `db:"status"`
	Input     string          `db:"input"`  // JSON
	Output    string          `db:"output"` // JSON
	Error     string          `db:"error"`
	StartedAt time.Time       `db:"started_at"`
	EndedAt   *time.Time      `db:"ended_at"`
	CreatedAt time.Time       `db:"created_at"`
}

// NodeExecution tracks execution of a single pipeline node.
type NodeExecution struct {
	ID          uuid.UUID       `db:"id"`
	ExecutionID uuid.UUID       `db:"execution_id"`
	NodeID      string          `db:"node_id"`
	NodeType    string          `db:"node_type"`
	Status      ExecutionStatus `db:"status"`
	Input       string          `db:"input"`
	Output      string          `db:"output"`
	Error       string          `db:"error"`
	StartedAt   time.Time       `db:"started_at"`
	EndedAt     *time.Time      `db:"ended_at"`
}

// AgentExecutionResponse is the DTO for an execution.
type AgentExecutionResponse struct {
	ID        uuid.UUID       `json:"id"`
	AgentID   uuid.UUID       `json:"agentId"`
	TenantID  string          `json:"tenantId"`
	Status    ExecutionStatus `json:"status"`
	Input     string          `json:"input"`
	Output    string          `json:"output"`
	Error     string          `json:"error"`
	StartedAt time.Time       `json:"startedAt"`
	EndedAt   *time.Time      `json:"endedAt"`
	CreatedAt time.Time       `json:"createdAt"`
}

// ResponseFrom maps AgentExecution to DTO.
func ResponseFrom(e AgentExecution) AgentExecutionResponse {
	return AgentExecutionResponse(e)
}

// CreateExecutionRequest is the payload to start an execution.
type CreateExecutionRequest struct {
	AgentID uuid.UUID      `json:"agentId"`
	Input   map[string]any `json:"input"`
}

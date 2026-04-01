package execution

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Repository handles persistence for AgentExecution records.
type Repository struct {
	pool *pgxpool.Pool
}

// NewRepository creates a Repository backed by pool.
func NewRepository(pool *pgxpool.Pool) *Repository {
	return &Repository{pool: pool}
}

// acquireWithTenant acquires a connection and sets the tenant search_path.
func (r *Repository) acquireWithTenant(ctx context.Context, tenantID string) (*pgxpool.Conn, error) {
	conn, err := r.pool.Acquire(ctx)
	if err != nil {
		return nil, fmt.Errorf("repository: acquire conn: %w", err)
	}
	if _, err := conn.Exec(ctx, fmt.Sprintf("SET search_path TO ah_%s, public", tenantID)); err != nil {
		conn.Release()
		return nil, fmt.Errorf("repository: set search_path: %w", err)
	}
	return conn, nil
}

// Create inserts a new AgentExecution.
func (r *Repository) Create(ctx context.Context, tenantID string, e AgentExecution) (AgentExecution, error) {
	conn, err := r.acquireWithTenant(ctx, tenantID)
	if err != nil {
		return AgentExecution{}, err
	}
	defer conn.Release()

	e.ID = uuid.New()
	e.CreatedAt = time.Now()
	e.StartedAt = time.Now()

	_, err = conn.Exec(ctx,
		`INSERT INTO agent_execution (id, agent_id, status, input, output, error, started_at, ended_at, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		e.ID, e.AgentID, e.Status, e.Input, e.Output, e.Error, e.StartedAt, e.EndedAt, e.CreatedAt,
	)
	if err != nil {
		return AgentExecution{}, fmt.Errorf("repository: create execution: %w", err)
	}
	return e, nil
}

// UpdateStatus updates the status and optionally sets ended_at.
func (r *Repository) UpdateStatus(ctx context.Context, tenantID string, id uuid.UUID, status ExecutionStatus, errMsg string) error {
	conn, err := r.acquireWithTenant(ctx, tenantID)
	if err != nil {
		return err
	}
	defer conn.Release()

	now := time.Now()
	var endedAt *time.Time
	if IsTerminal(status) {
		endedAt = &now
	}

	_, err = conn.Exec(ctx,
		`UPDATE agent_execution SET status=$1, error=$2, ended_at=$3 WHERE id=$4`,
		status, errMsg, endedAt, id,
	)
	return err
}

// GetByID returns an execution by ID.
func (r *Repository) GetByID(ctx context.Context, tenantID string, id uuid.UUID) (AgentExecution, error) {
	conn, err := r.acquireWithTenant(ctx, tenantID)
	if err != nil {
		return AgentExecution{}, err
	}
	defer conn.Release()

	row := conn.QueryRow(ctx,
		`SELECT id, agent_id, status, input, output, error, started_at, ended_at, created_at
         FROM agent_execution WHERE id=$1`,
		id,
	)
	var e AgentExecution
	if err := row.Scan(&e.ID, &e.AgentID, &e.Status, &e.Input, &e.Output, &e.Error, &e.StartedAt, &e.EndedAt, &e.CreatedAt); err != nil {
		return AgentExecution{}, fmt.Errorf("repository: get execution: %w", err)
	}
	e.TenantID = tenantID
	return e, nil
}

// ListByAgent returns paginated executions for an agent.
func (r *Repository) ListByAgent(ctx context.Context, tenantID string, agentID uuid.UUID, offset, limit int) ([]AgentExecution, int, error) {
	conn, err := r.acquireWithTenant(ctx, tenantID)
	if err != nil {
		return nil, 0, err
	}
	defer conn.Release()

	var total int
	if err := conn.QueryRow(ctx,
		`SELECT COUNT(*) FROM agent_execution WHERE agent_id=$1`, agentID,
	).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("repository: count executions: %w", err)
	}

	rows, err := conn.Query(ctx,
		`SELECT id, agent_id, status, input, output, error, started_at, ended_at, created_at
         FROM agent_execution WHERE agent_id=$1 ORDER BY created_at DESC LIMIT $2 OFFSET $3`,
		agentID, limit, offset,
	)
	if err != nil {
		return nil, 0, fmt.Errorf("repository: list executions: %w", err)
	}
	defer rows.Close()

	var results []AgentExecution
	for rows.Next() {
		var e AgentExecution
		if err := rows.Scan(&e.ID, &e.AgentID, &e.Status, &e.Input, &e.Output, &e.Error, &e.StartedAt, &e.EndedAt, &e.CreatedAt); err != nil {
			return nil, 0, err
		}
		e.TenantID = tenantID
		results = append(results, e)
	}
	return results, total, nil
}

package execution

import (
	"sync"

	"github.com/google/uuid"
)

// PipelineContext holds the shared state for a pipeline execution.
// Each node writes to its namespace; downstream nodes read via nodeResults.
type PipelineContext struct {
	ExecutionID uuid.UUID
	TenantID    string
	Input       map[string]any
	mu          sync.RWMutex
	nodeResults map[string]map[string]any // nodeID -> output
}

// NewPipelineContext creates a new PipelineContext for the given execution.
func NewPipelineContext(executionID uuid.UUID, tenantID string, input map[string]any) *PipelineContext {
	return &PipelineContext{
		ExecutionID: executionID,
		TenantID:    tenantID,
		Input:       input,
		nodeResults: make(map[string]map[string]any),
	}
}

// SetNodeOutput stores the output for a node.
func (c *PipelineContext) SetNodeOutput(nodeID string, output map[string]any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.nodeResults[nodeID] = output
}

// GetNodeOutput returns the output stored for nodeID.
func (c *PipelineContext) GetNodeOutput(nodeID string) (map[string]any, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	out, ok := c.nodeResults[nodeID]
	return out, ok
}

// Snapshot returns a copy of all node results.
func (c *PipelineContext) Snapshot() map[string]map[string]any {
	c.mu.RLock()
	defer c.mu.RUnlock()
	snap := make(map[string]map[string]any, len(c.nodeResults))
	for k, v := range c.nodeResults {
		snap[k] = v
	}
	return snap
}

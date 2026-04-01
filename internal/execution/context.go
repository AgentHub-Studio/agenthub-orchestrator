package execution

import (
	"encoding/json"
	"fmt"
	"strings"
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

// MarshalJSON serializes the context (node results only) for checkpoint persistence.
func (c *PipelineContext) MarshalJSON() ([]byte, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return json.Marshal(c.nodeResults)
}

// UnmarshalJSON restores node results from a checkpoint.
func (c *PipelineContext) UnmarshalJSON(data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return json.Unmarshal(data, &c.nodeResults)
}

// ResolveTemplate replaces {{nodeResults.<nodeID>.<field>}} expressions with values
// from the context. Nested fields are supported via dot notation.
// Example: "{{nodeResults.llm_1.response}}" resolves to the value of nodeResults["llm_1"]["response"].
func (c *PipelineContext) ResolveTemplate(tmpl string) (string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	result := tmpl
	for {
		start := strings.Index(result, "{{")
		if start == -1 {
			break
		}
		end := strings.Index(result[start:], "}}")
		if end == -1 {
			return "", fmt.Errorf("context: unclosed template expression in %q", tmpl)
		}
		end += start

		expr := strings.TrimSpace(result[start+2 : end])
		val, err := resolveExpr(expr, c.nodeResults, c.Input)
		if err != nil {
			return "", fmt.Errorf("context: %w", err)
		}
		result = result[:start] + fmt.Sprintf("%v", val) + result[end+2:]
	}
	return result, nil
}

// resolveExpr navigates a dot-separated path starting with "nodeResults.<nodeID>.<field>..."
// or "input.<field>...".
func resolveExpr(expr string, nodeResults map[string]map[string]any, input map[string]any) (any, error) {
	parts := strings.SplitN(expr, ".", 2)
	switch parts[0] {
	case "nodeResults":
		if len(parts) < 2 {
			return nil, fmt.Errorf("incomplete nodeResults path: %q", expr)
		}
		sub := strings.SplitN(parts[1], ".", 2)
		nodeID := sub[0]
		out, ok := nodeResults[nodeID]
		if !ok {
			return nil, fmt.Errorf("node %q not found in context", nodeID)
		}
		if len(sub) < 2 {
			return out, nil
		}
		return navigateMap(out, sub[1])
	case "input":
		if len(parts) < 2 {
			return input, nil
		}
		return navigateMap(input, parts[1])
	default:
		return nil, fmt.Errorf("unknown root %q in expression %q (use nodeResults or input)", parts[0], expr)
	}
}

// navigateMap walks a dot-separated path through nested map[string]any.
func navigateMap(m map[string]any, path string) (any, error) {
	parts := strings.SplitN(path, ".", 2)
	val, ok := m[parts[0]]
	if !ok {
		return nil, fmt.Errorf("field %q not found", parts[0])
	}
	if len(parts) == 1 {
		return val, nil
	}
	nested, ok := val.(map[string]any)
	if !ok {
		return nil, fmt.Errorf("field %q is not a map, cannot navigate deeper", parts[0])
	}
	return navigateMap(nested, parts[1])
}

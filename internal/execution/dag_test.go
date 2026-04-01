package execution

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestTopologicalSort_Simple(t *testing.T) {
	nodes := []*Node{
		{ID: "a", Type: "INPUT"},
		{ID: "b", Type: "TRANSFORM"},
		{ID: "c", Type: "OUTPUT"},
	}
	edges := []Edge{
		{Source: "a", Target: "b"},
		{Source: "b", Target: "c"},
	}
	dag := NewDAG(nodes, edges)

	sorted, err := dag.TopologicalSort()
	require.NoError(t, err)
	assert.Len(t, sorted, 3)
	// a must come before b, b before c
	indexOf := func(id string) int {
		for i, n := range sorted {
			if n.ID == id {
				return i
			}
		}
		return -1
	}
	assert.Less(t, indexOf("a"), indexOf("b"))
	assert.Less(t, indexOf("b"), indexOf("c"))
}

func TestTopologicalSort_CycleDetected(t *testing.T) {
	nodes := []*Node{
		{ID: "a", Type: "INPUT"},
		{ID: "b", Type: "TRANSFORM"},
	}
	edges := []Edge{
		{Source: "a", Target: "b"},
		{Source: "b", Target: "a"}, // cycle
	}
	dag := NewDAG(nodes, edges)

	_, err := dag.TopologicalSort()
	assert.Error(t, err)
}

func TestLevels_Parallel(t *testing.T) {
	// a → c, b → c (a and b can run in parallel, c waits for both)
	nodes := []*Node{
		{ID: "a", Type: "INPUT"},
		{ID: "b", Type: "INPUT"},
		{ID: "c", Type: "OUTPUT"},
	}
	edges := []Edge{
		{Source: "a", Target: "c"},
		{Source: "b", Target: "c"},
	}
	dag := NewDAG(nodes, edges)

	levels, err := dag.Levels()
	require.NoError(t, err)
	assert.Len(t, levels, 2)
	assert.Len(t, levels[0], 2) // a and b in level 0
	assert.Len(t, levels[1], 1) // c in level 1
}

func TestStateMachine_ValidTransitions(t *testing.T) {
	assert.NoError(t, Transition(StatusPending, StatusRunning))
	assert.NoError(t, Transition(StatusRunning, StatusCompleted))
	assert.NoError(t, Transition(StatusRunning, StatusFailed))
	assert.NoError(t, Transition(StatusRunning, StatusCancelled))
}

func TestStateMachine_InvalidTransitions(t *testing.T) {
	assert.Error(t, Transition(StatusCompleted, StatusRunning))
	assert.Error(t, Transition(StatusFailed, StatusRunning))
	assert.Error(t, Transition(StatusPending, StatusCompleted))
}

func TestIsTerminal(t *testing.T) {
	assert.True(t, IsTerminal(StatusCompleted))
	assert.True(t, IsTerminal(StatusFailed))
	assert.True(t, IsTerminal(StatusCancelled))
	assert.False(t, IsTerminal(StatusPending))
	assert.False(t, IsTerminal(StatusRunning))
}

func TestPipelineContext_NodeOutput(t *testing.T) {
	pctx := NewPipelineContext(
		[16]byte{},
		"test-tenant",
		map[string]any{"key": "value"},
	)

	pctx.SetNodeOutput("node1", map[string]any{"result": 42})

	out, ok := pctx.GetNodeOutput("node1")
	assert.True(t, ok)
	assert.Equal(t, 42, out["result"])

	_, ok = pctx.GetNodeOutput("nonexistent")
	assert.False(t, ok)
}

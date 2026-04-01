package execution

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// successExecutor always succeeds and records which nodes executed.
type successExecutor struct {
	executed atomic.Int64
}

func (e *successExecutor) Execute(_ context.Context, _ *Node, _ *PipelineContext) (map[string]any, error) {
	e.executed.Add(1)
	return map[string]any{"ok": true}, nil
}

// failExecutor always returns an error.
type failExecutor struct{}

func (e *failExecutor) Execute(_ context.Context, _ *Node, _ *PipelineContext) (map[string]any, error) {
	return nil, fmt.Errorf("node failed intentionally")
}

// slowExecutor sleeps for d before succeeding, used to test concurrency limits.
type slowExecutor struct {
	d       time.Duration
	started atomic.Int64
	peak    atomic.Int64
	running atomic.Int64
}

func (e *slowExecutor) Execute(_ context.Context, _ *Node, _ *PipelineContext) (map[string]any, error) {
	e.started.Add(1)
	cur := e.running.Add(1)
	// Track peak concurrency.
	for {
		old := e.peak.Load()
		if cur <= old || e.peak.CompareAndSwap(old, cur) {
			break
		}
	}
	time.Sleep(e.d)
	e.running.Add(-1)
	return map[string]any{"ok": true}, nil
}

func makeRegistry(executors map[string]NodeExecutor) *NodeRegistry {
	r := &NodeRegistry{executors: make(map[string]NodeExecutor)}
	for k, v := range executors {
		r.executors[k] = v
	}
	return r
}

// TestScheduler_LinearDAG verifies a simple A→B→C chain executes all nodes.
func TestScheduler_LinearDAG(t *testing.T) {
	exec := &successExecutor{}
	reg := makeRegistry(map[string]NodeExecutor{"TASK": exec})
	s := NewScheduler(reg)

	nodes := []*Node{
		{ID: "a", Type: "TASK", Deps: []string{}},
		{ID: "b", Type: "TASK", Deps: []string{"a"}},
		{ID: "c", Type: "TASK", Deps: []string{"b"}},
	}
	dag := NewDAG(nodes, []Edge{{Source: "a", Target: "b"}, {Source: "b", Target: "c"}})
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})

	err := s.Run(context.Background(), dag, pctx)
	require.NoError(t, err)
	assert.Equal(t, int64(3), exec.executed.Load())
}

// TestScheduler_ParallelNodes verifies independent nodes at the same level run concurrently.
func TestScheduler_ParallelNodes(t *testing.T) {
	slow := &slowExecutor{d: 50 * time.Millisecond}
	reg := makeRegistry(map[string]NodeExecutor{"SLOW": slow})
	s := NewScheduler(reg, 10) // allow up to 10 parallel

	// 3 independent nodes — all should run in parallel.
	nodes := []*Node{
		{ID: "a", Type: "SLOW", Deps: []string{}},
		{ID: "b", Type: "SLOW", Deps: []string{}},
		{ID: "c", Type: "SLOW", Deps: []string{}},
	}
	dag := NewDAG(nodes, nil)
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})

	start := time.Now()
	err := s.Run(context.Background(), dag, pctx)
	elapsed := time.Since(start)

	require.NoError(t, err)
	assert.Equal(t, int64(3), slow.started.Load())
	// If truly parallel, elapsed should be ~50ms not ~150ms.
	assert.Less(t, elapsed, 120*time.Millisecond, "nodes should run in parallel")
}

// TestScheduler_MaxParallelNodes verifies the semaphore limits concurrency.
func TestScheduler_MaxParallelNodes(t *testing.T) {
	slow := &slowExecutor{d: 30 * time.Millisecond}
	reg := makeRegistry(map[string]NodeExecutor{"SLOW": slow})
	s := NewScheduler(reg, 2) // allow max 2 in parallel

	nodes := []*Node{
		{ID: "a", Type: "SLOW", Deps: []string{}},
		{ID: "b", Type: "SLOW", Deps: []string{}},
		{ID: "c", Type: "SLOW", Deps: []string{}},
		{ID: "d", Type: "SLOW", Deps: []string{}},
	}
	dag := NewDAG(nodes, nil)
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})

	err := s.Run(context.Background(), dag, pctx)
	require.NoError(t, err)
	assert.Equal(t, int64(4), slow.started.Load())
	// Peak concurrent goroutines must not exceed maxParallelNodes=2.
	assert.LessOrEqual(t, slow.peak.Load(), int64(2))
}

// TestScheduler_NodeError propagates the first error and stops execution.
func TestScheduler_NodeError(t *testing.T) {
	fail := &failExecutor{}
	reg := makeRegistry(map[string]NodeExecutor{"FAIL": fail})
	s := NewScheduler(reg)

	dag := NewDAG([]*Node{{ID: "x", Type: "FAIL"}}, nil)
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})

	err := s.Run(context.Background(), dag, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "node failed intentionally")
}

// TestScheduler_ContextCancellation stops at the next level when ctx is cancelled.
func TestScheduler_ContextCancellation(t *testing.T) {
	exec := &successExecutor{}
	reg := makeRegistry(map[string]NodeExecutor{"TASK": exec})
	s := NewScheduler(reg)

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	dag := NewDAG([]*Node{{ID: "a", Type: "TASK"}, {ID: "b", Type: "TASK", Deps: []string{"a"}}},
		[]Edge{{Source: "a", Target: "b"}})
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})

	err := s.Run(ctx, dag, pctx)
	require.Error(t, err)
}

// TestScheduler_DefaultMaxParallel verifies default 8 is used when not specified.
func TestScheduler_DefaultMaxParallel(t *testing.T) {
	reg := makeRegistry(map[string]NodeExecutor{})
	s := NewScheduler(reg)
	assert.Equal(t, defaultMaxParallelNodes, s.maxParallelNodes)
}

// TestScheduler_CycleDetected returns error for cyclic DAGs.
func TestScheduler_CycleDetected(t *testing.T) {
	exec := &successExecutor{}
	reg := makeRegistry(map[string]NodeExecutor{"TASK": exec})
	s := NewScheduler(reg)

	nodes := []*Node{
		{ID: "a", Type: "TASK", Deps: []string{"b"}},
		{ID: "b", Type: "TASK", Deps: []string{"a"}},
	}
	dag := NewDAG(nodes, []Edge{{Source: "a", Target: "b"}, {Source: "b", Target: "a"}})
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})

	err := s.Run(context.Background(), dag, pctx)
	require.Error(t, err)
}

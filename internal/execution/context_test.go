package execution_test

import (
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/execution"
)

func TestPipelineContext_SetAndGet(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "tenant-1", map[string]any{"query": "hello"})
	ctx.SetNodeOutput("node-A", map[string]any{"result": "42"})

	out, ok := ctx.GetNodeOutput("node-A")
	require.True(t, ok)
	assert.Equal(t, "42", out["result"])
}

func TestPipelineContext_GetMissing(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "tenant-1", nil)
	_, ok := ctx.GetNodeOutput("does-not-exist")
	assert.False(t, ok)
}

func TestPipelineContext_Snapshot(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "tenant-1", nil)
	ctx.SetNodeOutput("n1", map[string]any{"x": 1})
	ctx.SetNodeOutput("n2", map[string]any{"y": 2})

	snap := ctx.Snapshot()
	assert.Len(t, snap, 2)
	assert.Equal(t, 1, snap["n1"]["x"])
	assert.Equal(t, 2, snap["n2"]["y"])
}

func TestPipelineContext_SnapshotTopLevelIsolation(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "tenant-1", nil)
	ctx.SetNodeOutput("n1", map[string]any{"v": "original"})

	snap := ctx.Snapshot()

	// Adding a new key to the snapshot map should not add it to the live context.
	snap["n2"] = map[string]any{"added": true}

	_, ok := ctx.GetNodeOutput("n2")
	assert.False(t, ok, "snapshot top-level key should not affect live context")
}

func TestPipelineContext_ConcurrentWrites(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "tenant-1", nil)
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			nodeID := uuid.New().String()
			ctx.SetNodeOutput(nodeID, map[string]any{"i": i})
		}(i)
	}
	wg.Wait()

	snap := ctx.Snapshot()
	assert.Len(t, snap, 50, "all 50 concurrent writes should be recorded")
}

func TestPipelineContext_InputPreserved(t *testing.T) {
	input := map[string]any{"prompt": "test"}
	ctx := execution.NewPipelineContext(uuid.New(), "tenant-1", input)
	assert.Equal(t, "test", ctx.Input["prompt"])
}

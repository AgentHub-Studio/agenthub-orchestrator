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

// --- ResolveTemplate ---

func TestResolveTemplate_SimpleField(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", nil)
	ctx.SetNodeOutput("llm_1", map[string]any{"response": "Hello!"})

	got, err := ctx.ResolveTemplate("Result: {{nodeResults.llm_1.response}}")
	require.NoError(t, err)
	assert.Equal(t, "Result: Hello!", got)
}

func TestResolveTemplate_NestedField(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", nil)
	ctx.SetNodeOutput("llm_1", map[string]any{"meta": map[string]any{"tokens": 42}})

	got, err := ctx.ResolveTemplate("{{nodeResults.llm_1.meta.tokens}}")
	require.NoError(t, err)
	assert.Equal(t, "42", got)
}

func TestResolveTemplate_InputField(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", map[string]any{"query": "hello"})

	got, err := ctx.ResolveTemplate("Query: {{input.query}}")
	require.NoError(t, err)
	assert.Equal(t, "Query: hello", got)
}

func TestResolveTemplate_MultipleExpressions(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", map[string]any{"lang": "go"})
	ctx.SetNodeOutput("n1", map[string]any{"val": "42"})

	got, err := ctx.ResolveTemplate("{{input.lang}}-{{nodeResults.n1.val}}")
	require.NoError(t, err)
	assert.Equal(t, "go-42", got)
}

func TestResolveTemplate_NoExpression(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", nil)
	got, err := ctx.ResolveTemplate("plain text")
	require.NoError(t, err)
	assert.Equal(t, "plain text", got)
}

func TestResolveTemplate_UnknownNode_ReturnsError(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", nil)
	_, err := ctx.ResolveTemplate("{{nodeResults.missing_node.field}}")
	require.Error(t, err)
}

func TestResolveTemplate_UnclosedBrace_ReturnsError(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", nil)
	_, err := ctx.ResolveTemplate("{{nodeResults.n1.field")
	require.Error(t, err)
}

// --- JSON round-trip ---

func TestPipelineContext_JSONRoundTrip(t *testing.T) {
	ctx := execution.NewPipelineContext(uuid.New(), "t1", nil)
	ctx.SetNodeOutput("n1", map[string]any{"a": float64(1)})
	ctx.SetNodeOutput("n2", map[string]any{"b": "hello"})

	data, err := ctx.MarshalJSON()
	require.NoError(t, err)

	ctx2 := execution.NewPipelineContext(uuid.New(), "t1", nil)
	err = ctx2.UnmarshalJSON(data)
	require.NoError(t, err)

	out, ok := ctx2.GetNodeOutput("n1")
	require.True(t, ok)
	assert.Equal(t, float64(1), out["a"])
}

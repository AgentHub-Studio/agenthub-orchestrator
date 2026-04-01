package execution

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- IF executor tests ---

func TestIfExecutor_ExprTrue(t *testing.T) {
	exec := &ifExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"score": 0.9})

	node := &Node{ID: "if1", Type: "IF", Config: map[string]any{
		"condition":   "score > 0.8",
		"inputNodeId": "prev",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "true", out["branch"])
}

func TestIfExecutor_ExprFalse(t *testing.T) {
	exec := &ifExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"score": 0.5})

	node := &Node{ID: "if2", Type: "IF", Config: map[string]any{
		"condition":   "score > 0.8",
		"inputNodeId": "prev",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "false", out["branch"])
}

func TestIfExecutor_StringEquality(t *testing.T) {
	exec := &ifExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"status": "active"})

	node := &Node{ID: "if3", Type: "IF", Config: map[string]any{
		"condition":   `status == "active"`,
		"inputNodeId": "prev",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "true", out["branch"])
}

func TestIfExecutor_LogicalAnd(t *testing.T) {
	exec := &ifExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"score": 0.9, "enabled": true})

	node := &Node{ID: "if4", Type: "IF", Config: map[string]any{
		"condition":   "score > 0.5 && enabled == true",
		"inputNodeId": "prev",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "true", out["branch"])
}

func TestIfExecutor_EmptyCondition_DefaultsTrue(t *testing.T) {
	exec := &ifExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	node := &Node{ID: "if5", Type: "IF", Config: map[string]any{}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "true", out["branch"])
}

func TestIfExecutor_InvalidExpression_ReturnsError(t *testing.T) {
	exec := &ifExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{})
	node := &Node{ID: "if6", Type: "IF", Config: map[string]any{
		"condition":   "%%%invalid%%%",
		"inputNodeId": "prev",
	}}
	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
}

// --- SWITCH executor tests ---

func TestSwitchExecutor_MatchesCase(t *testing.T) {
	exec := &switchExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"tier": "gold"})

	node := &Node{ID: "sw1", Type: "SWITCH", Config: map[string]any{
		"expression":  "tier",
		"inputNodeId": "prev",
		"cases":       map[string]any{"gold": "premium", "silver": "standard"},
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "gold", out["value"])
	assert.Equal(t, "premium", out["branch"])
}

func TestSwitchExecutor_DefaultBranch(t *testing.T) {
	exec := &switchExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"tier": "bronze"})

	node := &Node{ID: "sw2", Type: "SWITCH", Config: map[string]any{
		"expression":  "tier",
		"inputNodeId": "prev",
		"cases":       map[string]any{"gold": "premium"},
		"default":     "fallback",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "fallback", out["branch"])
}

func TestSwitchExecutor_ExpressionResult(t *testing.T) {
	exec := &switchExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"count": 15})

	node := &Node{ID: "sw3", Type: "SWITCH", Config: map[string]any{
		"expression":  `count > 10 ? "high" : "low"`,
		"inputNodeId": "prev",
		"cases":       map[string]any{"high": "large-batch", "low": "small-batch"},
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "large-batch", out["branch"])
}

// --- MERGE executor tests ---

func TestMergeExecutor_MergeAll(t *testing.T) {
	exec := &mergeExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("n1", map[string]any{"a": 1})
	pctx.SetNodeOutput("n2", map[string]any{"b": 2})

	node := &Node{ID: "m1", Type: "MERGE", Config: map[string]any{
		"sources":  []any{"n1", "n2"},
		"strategy": "MERGE_ALL",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 1, out["a"])
	assert.Equal(t, 2, out["b"])
}

func TestMergeExecutor_First(t *testing.T) {
	exec := &mergeExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("n1", map[string]any{"val": "first"})
	pctx.SetNodeOutput("n2", map[string]any{"val": "second"})

	node := &Node{ID: "m2", Type: "MERGE", Config: map[string]any{
		"sources":  []any{"n1", "n2"},
		"strategy": "FIRST",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "first", out["val"])
}

func TestMergeExecutor_Last(t *testing.T) {
	exec := &mergeExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("n1", map[string]any{"val": "first"})
	pctx.SetNodeOutput("n2", map[string]any{"val": "second"})

	node := &Node{ID: "m3", Type: "MERGE", Config: map[string]any{
		"sources":  []any{"n1", "n2"},
		"strategy": "LAST",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "second", out["val"])
}

func TestMergeExecutor_Concat(t *testing.T) {
	exec := &mergeExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("n1", map[string]any{"val": "a"})
	pctx.SetNodeOutput("n2", map[string]any{"val": "b"})

	node := &Node{ID: "m4", Type: "MERGE", Config: map[string]any{
		"sources":  []any{"n1", "n2"},
		"strategy": "CONCAT",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 2, out["count"])
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 2)
}

func TestMergeExecutor_DefaultStrategy_IsMergeAll(t *testing.T) {
	exec := &mergeExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("n1", map[string]any{"x": 10})
	pctx.SetNodeOutput("n2", map[string]any{"y": 20})

	node := &Node{ID: "m5", Type: "MERGE", Config: map[string]any{
		"sources": []any{"n1", "n2"},
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 10, out["x"])
	assert.Equal(t, 20, out["y"])
}

// --- FOREACH executor tests ---

func TestForeachExecutor_ParallelItems(t *testing.T) {
	exec := &foreachExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"items": []any{"a", "b", "c"}})

	node := &Node{ID: "fe1", Type: "FOREACH", Config: map[string]any{
		"inputNodeId": "prev",
		"itemsKey":    "items",
		"concurrency": 3,
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 3, out["count"])
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 3)
}

func TestForeachExecutor_EmptyItems(t *testing.T) {
	exec := &foreachExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"items": []any{}})

	node := &Node{ID: "fe2", Type: "FOREACH", Config: map[string]any{
		"inputNodeId": "prev",
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 0, out["count"])
}

func TestForeachExecutor_PreservesOrder(t *testing.T) {
	exec := &foreachExecutor{}
	pctx := NewPipelineContext(uuid.Nil, "tenant", map[string]any{})
	items := []any{0, 1, 2, 3, 4}
	pctx.SetNodeOutput("prev", map[string]any{"items": items})

	node := &Node{ID: "fe3", Type: "FOREACH", Config: map[string]any{
		"inputNodeId": "prev",
		"concurrency": 5,
	}}
	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	results := out["results"].([]any)
	for i, r := range results {
		m := r.(map[string]any)
		assert.Equal(t, i, m["index"])
	}
}

package execution

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- helpers ---

func makeNode(id, nodeType string, cfg map[string]any, deps ...string) *Node {
	return &Node{ID: id, Type: nodeType, Config: cfg, Deps: deps}
}

func makeCtx(input map[string]any) *PipelineContext {
	return NewPipelineContext(uuid.New(), "test-tenant", input)
}

// --- INPUT ---

func TestInputExecutor_ReturnsContextInput(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("INPUT")
	require.NoError(t, err)

	input := map[string]any{"query": "hello", "user": "alice"}
	pctx := makeCtx(input)
	node := makeNode("n1", "INPUT", nil)

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "hello", out["query"])
	assert.Equal(t, "alice", out["user"])
}

// --- OUTPUT ---

func TestOutputExecutor_ReturnsUpstreamResult(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("OUTPUT")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"result": "ok", "score": 42})

	node := makeNode("n2", "OUTPUT", map[string]any{
		"sources": []any{"upstream"},
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "ok", out["result"])
	assert.Equal(t, 42, out["score"])
}

func TestOutputExecutor_NoDependency(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("OUTPUT")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	node := makeNode("n2", "OUTPUT", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Empty(t, out)
}

// --- TRANSFORM ---

func TestTransformExecutor_MergesInputs(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("TRANSFORM")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("n1", map[string]any{"key1": "val1"})
	pctx.SetNodeOutput("n2", map[string]any{"key2": "val2"})

	// No mappings config → merges all upstream outputs.
	node := makeNode("n3", "TRANSFORM", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "val1", out["key1"])
	assert.Equal(t, "val2", out["key2"])
}

func TestTransformExecutor_EmptyDeps(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("TRANSFORM")
	require.NoError(t, err)

	pctx := makeCtx(nil) // no node outputs set
	node := makeNode("n3", "TRANSFORM", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Empty(t, out)
}

// --- IF ---

func TestIfExecutor_ConditionTrue(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("IF")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"status": "active"})

	node := makeNode("n1", "IF", map[string]any{
		"condition":   "status==active",
		"inputNodeId": "upstream",
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "true", out["branch"])
}

func TestIfExecutor_ConditionFalse(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("IF")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"status": "inactive"})

	node := makeNode("n1", "IF", map[string]any{
		"condition":   "status==active",
		"inputNodeId": "upstream",
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "false", out["branch"])
}

func TestIfExecutor_MissingCondition(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("IF")
	require.NoError(t, err)

	// No condition in config → evaluateCondition falls through to default "true".
	pctx := makeCtx(nil)
	node := makeNode("n1", "IF", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	// No error is expected: executor defaults to branch "true".
	require.NoError(t, err)
	assert.Equal(t, "true", out["branch"])
}

// --- FOREACH ---

func TestForeachExecutor_ItemsList(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("FOREACH")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"items": []any{1, 2, 3}})

	node := makeNode("n1", "FOREACH", map[string]any{
		"inputNodeId": "upstream",
		"itemsKey":    "items",
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 3, out["count"])
	assert.Equal(t, "foreach_ready", out["status"])
	items, ok := out["items"].([]any)
	require.True(t, ok)
	assert.Len(t, items, 3)
}

func TestForeachExecutor_EmptyItems(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("FOREACH")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	// No upstream node output → items will be nil/empty.
	node := makeNode("n1", "FOREACH", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	// Executor returns count 0 without error.
	require.NoError(t, err)
	assert.Equal(t, 0, out["count"])
}

// --- SWITCH ---

func TestSwitchExecutor_MatchesCase(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("SWITCH")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"plan": "premium"})

	node := makeNode("n1", "SWITCH", map[string]any{
		"inputNodeId": "upstream",
		"valueKey":    "plan",
		"cases": map[string]any{
			"premium": "premium-branch",
			"basic":   "basic-branch",
		},
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "premium-branch", out["branch"])
	assert.Equal(t, "premium", out["value"])
}

func TestSwitchExecutor_DefaultCase(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("SWITCH")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"plan": "unknown"})

	node := makeNode("n1", "SWITCH", map[string]any{
		"inputNodeId": "upstream",
		"valueKey":    "plan",
		"cases": map[string]any{
			"premium": "premium-branch",
		},
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	// No matching case → falls through to "default".
	assert.Equal(t, "default", out["branch"])
}

func TestSwitchExecutor_NoMatch(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("SWITCH")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	// No upstream node output → value is empty string, no cases defined.
	node := makeNode("n1", "SWITCH", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	// No cases → branch defaults to "default".
	assert.Equal(t, "default", out["branch"])
}

// --- MERGE ---

func TestMergeExecutor_CombinesDeps(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("MERGE")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("n1", map[string]any{"a": 1})
	pctx.SetNodeOutput("n2", map[string]any{"b": 2})

	node := makeNode("n3", "MERGE", map[string]any{
		"sources": []any{"n1", "n2"},
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 1, out["a"])
	assert.Equal(t, 2, out["b"])
}

// --- JOIN ---

func TestJoinExecutor_CollectsDeps(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("JOIN")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("n1", map[string]any{"x": "foo"})
	pctx.SetNodeOutput("n2", map[string]any{"y": "bar"})

	node := makeNode("n3", "JOIN", map[string]any{
		"sources": []any{"n1", "n2"},
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 2, out["count"])
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 2)
}

// --- APPROVAL ---

func TestApprovalExecutor_AlwaysPending(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("APPROVAL")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	node := makeNode("approval-1", "APPROVAL", map[string]any{
		"message": "Please approve before proceeding",
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "pending_approval", out["status"])
	assert.Equal(t, "Please approve before proceeding", out["message"])
	approvalID, ok := out["approvalId"].(string)
	require.True(t, ok)
	assert.Contains(t, approvalID, "approval-")
}

// --- TOOL (HTTP call to skill-runtime) ---

func TestToolExecutor_CallsSkillRuntime(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "POST", r.Method)
		assert.Contains(t, r.URL.Path, "/api/v1/skills/")
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"result": "ok"}) //nolint:errcheck
	}))
	defer srv.Close()

	registry := NewNodeRegistry(nil, srv.URL)
	exec, err := registry.Get("TOOL")
	require.NoError(t, err)

	node := makeNode("n1", "TOOL", map[string]any{"skillSlug": "my-skill"})
	pctx := makeCtx(map[string]any{"query": "test"})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "ok", out["result"])
}

func TestToolExecutor_MissingSkillSlug(t *testing.T) {
	registry := NewNodeRegistry(nil, "http://localhost:9999")
	exec, err := registry.Get("TOOL")
	require.NoError(t, err)

	node := makeNode("n1", "TOOL", map[string]any{}) // no skillSlug
	pctx := makeCtx(nil)

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skillSlug")
}

func TestToolExecutor_MissingRuntimeURL(t *testing.T) {
	registry := NewNodeRegistry(nil, "") // empty URL
	exec, err := registry.Get("TOOL")
	require.NoError(t, err)

	node := makeNode("n1", "TOOL", map[string]any{"skillSlug": "my-skill"})
	pctx := makeCtx(nil)

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skill-runtime URL not configured")
}

// --- HTTP (stub) ---

func TestHTTPExecutor_ReturnsQueuedStatus(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("HTTP")
	require.NoError(t, err)

	node := makeNode("n1", "HTTP", map[string]any{
		"method": "GET",
		"url":    "https://example.com/api",
	})
	pctx := makeCtx(nil)

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "queued", out["status"])
	assert.Equal(t, "GET", out["method"])
	assert.Equal(t, "https://example.com/api", out["url"])
}

func TestHTTPExecutor_MissingURL(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("HTTP")
	require.NoError(t, err)

	node := makeNode("n1", "HTTP", map[string]any{})
	pctx := makeCtx(nil)

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "url")
}

// --- SQL (stub) ---

func TestSQLExecutor_ReturnsQueuedStatus(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("SQL")
	require.NoError(t, err)

	node := makeNode("n1", "SQL", map[string]any{
		"query": "SELECT * FROM users WHERE id = :id",
	})
	pctx := makeCtx(nil)

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "queued", out["status"])
	assert.Equal(t, "SELECT * FROM users WHERE id = :id", out["query"])
}

func TestSQLExecutor_MissingQuery(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("SQL")
	require.NoError(t, err)

	node := makeNode("n1", "SQL", map[string]any{})
	pctx := makeCtx(nil)

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "query")
}

// --- DOCUMENT_SEARCH (stub) ---

func TestDocumentSearchExecutor_ReturnsDocuments(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("DOCUMENT_SEARCH")
	require.NoError(t, err)

	node := makeNode("n1", "DOCUMENT_SEARCH", map[string]any{
		"knowledgeBaseId": "kb-123",
		"query":           "golang testing",
		"topK":            float64(3),
	})
	pctx := makeCtx(nil)

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "kb-123", out["knowledgeBaseId"])
	assert.Equal(t, 3, out["topK"])
}

func TestDocumentSearchExecutor_MissingKnowledgeBaseID(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("DOCUMENT_SEARCH")
	require.NoError(t, err)

	node := makeNode("n1", "DOCUMENT_SEARCH", map[string]any{})
	pctx := makeCtx(nil)

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "knowledgeBaseId")
}

// --- EMBED ---

func TestEmbedExecutor_ReturnsTextAndNilEmbedding(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("EMBED")
	require.NoError(t, err)

	node := makeNode("n1", "EMBED", map[string]any{
		"text": "embed this text",
	})
	pctx := makeCtx(nil)

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "embed this text", out["text"])
	assert.Nil(t, out["embedding"])
}

// --- WEBHOOK_OUT ---

func TestWebhookOutExecutor_CallsEndpoint(t *testing.T) {
	var receivedBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "POST", r.Method)
		json.NewDecoder(r.Body).Decode(&receivedBody) //nolint:errcheck
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("WEBHOOK_OUT")
	require.NoError(t, err)

	pctx := makeCtx(nil)
	pctx.SetNodeOutput("upstream", map[string]any{"event": "order.created"})

	node := makeNode("n1", "WEBHOOK_OUT", map[string]any{
		"url":         srv.URL,
		"inputNodeId": "upstream",
	})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, true, out["delivered"])
	assert.Equal(t, http.StatusOK, out["status"])
}

func TestWebhookOutExecutor_MissingURL(t *testing.T) {
	registry := NewNodeRegistry(nil, "")
	exec, err := registry.Get("WEBHOOK_OUT")
	require.NoError(t, err)

	node := makeNode("n1", "WEBHOOK_OUT", map[string]any{})
	pctx := makeCtx(nil)

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "url")
}

// --- LLM ---

func TestLLMExecutor_NoProvider(t *testing.T) {
	registry := NewNodeRegistry(nil, "") // nil providerRegistry
	exec, err := registry.Get("LLM")
	require.NoError(t, err)

	node := makeNode("n1", "LLM", map[string]any{
		"provider": "openai",
		"model":    "gpt-4o",
		"prompt":   "hello",
	})
	pctx := makeCtx(map[string]any{"prompt": "hello"})

	_, err = exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no AI provider registry configured")
}

// --- NodeRegistry ---

func TestNewNodeRegistry_AllTypesRegistered(t *testing.T) {
	registry := NewNodeRegistry(nil, "")

	expectedTypes := []string{
		"INPUT", "OUTPUT", "TRANSFORM",
		"LLM", "EMBED",
		"SQL", "HTTP", "DOCUMENT_SEARCH", "TOOL",
		"IF", "FOREACH", "SWITCH", "MERGE", "JOIN",
		"WEBHOOK_OUT", "APPROVAL",
	}

	for _, nodeType := range expectedTypes {
		exec, err := registry.Get(nodeType)
		assert.NoError(t, err, "expected executor for type %q to be registered", nodeType)
		assert.NotNil(t, exec, "executor for type %q should not be nil", nodeType)
	}
}

func TestNodeRegistry_Get_UnknownType(t *testing.T) {
	registry := NewNodeRegistry(nil, "")

	_, err := registry.Get("UNKNOWN_TYPE")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "UNKNOWN_TYPE")
}

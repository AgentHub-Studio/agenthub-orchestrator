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

// TestToolExecutor_Execute_Success spins up a mock skill-runtime HTTP server and
// verifies that the TOOL node POSTs to /api/v1/skills/{slug}/execute and returns
// the response body as node output.
func TestToolExecutor_Execute_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/api/v1/skills/send-email/execute", r.URL.Path)
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))

		var reqBody map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&reqBody))
		input, _ := reqBody["input"].(map[string]any)
		assert.Equal(t, "hello@example.com", input["to"])

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"sent": true, "messageId": "msg-001"})
	}))
	defer srv.Close()

	exec := &toolExecutor{skillRuntimeURL: srv.URL}
	node := &Node{
		ID:   "tool1",
		Type: "TOOL",
		Config: map[string]any{
			"skillSlug":   "send-email",
			"inputNodeId": "upstream",
		},
	}
	pctx := NewPipelineContext(uuid.New(), "test-tenant", map[string]any{})
	pctx.SetNodeOutput("upstream", map[string]any{"to": "hello@example.com", "subject": "Hi"})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, true, out["sent"])
	assert.Equal(t, "msg-001", out["messageId"])
}

// TestToolExecutor_Execute_NoInputNode verifies execution succeeds when no
// inputNodeId is configured (empty input is passed to skill-runtime).
func TestToolExecutor_Execute_NoInputNode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var reqBody map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&reqBody))
		assert.Nil(t, reqBody["input"])

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}))
	defer srv.Close()

	exec := &toolExecutor{skillRuntimeURL: srv.URL}
	node := &Node{
		ID:   "tool2",
		Type: "TOOL",
		Config: map[string]any{
			"skillSlug": "ping",
		},
	}
	pctx := NewPipelineContext(uuid.New(), "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, true, out["ok"])
}

// TestToolExecutor_Execute_MissingSkillSlug verifies validation error when skillSlug is absent.
func TestToolExecutor_Execute_MissingSkillSlug(t *testing.T) {
	exec := &toolExecutor{skillRuntimeURL: "http://localhost:9999"}
	node := &Node{
		ID:     "tool3",
		Type:   "TOOL",
		Config: map[string]any{},
	}
	pctx := NewPipelineContext(uuid.New(), "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skillSlug")
}

// TestToolExecutor_Execute_MissingURL verifies error when skillRuntimeURL is empty.
func TestToolExecutor_Execute_MissingURL(t *testing.T) {
	exec := &toolExecutor{skillRuntimeURL: ""}
	node := &Node{
		ID:   "tool4",
		Type: "TOOL",
		Config: map[string]any{
			"skillSlug": "some-skill",
		},
	}
	pctx := NewPipelineContext(uuid.New(), "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skill-runtime URL not configured")
}

// TestToolExecutor_Execute_SkillRuntimeError verifies that a non-200 response
// from skill-runtime still decodes the body (error details forwarded to caller).
func TestToolExecutor_Execute_SkillRuntimeError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnprocessableEntity)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"error": "skill not found"})
	}))
	defer srv.Close()

	exec := &toolExecutor{skillRuntimeURL: srv.URL}
	node := &Node{
		ID:   "tool5",
		Type: "TOOL",
		Config: map[string]any{
			"skillSlug": "nonexistent",
		},
	}
	pctx := NewPipelineContext(uuid.New(), "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err) // decode succeeds even on error status
	assert.Equal(t, "skill not found", out["error"])
}

// TestToolExecutor_IntegrationWithNodeRegistry verifies that NodeRegistry correctly
// wires the toolExecutor with the provided skillRuntimeURL.
func TestToolExecutor_IntegrationWithNodeRegistry(t *testing.T) {
	called := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		assert.Equal(t, "/api/v1/skills/my-skill/execute", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"result": "done"})
	}))
	defer srv.Close()

	registry := NewNodeRegistry(nil, srv.URL, "")
	exec, err := registry.Get("TOOL")
	require.NoError(t, err)

	node := &Node{
		ID:   "tool6",
		Type: "TOOL",
		Config: map[string]any{
			"skillSlug": "my-skill",
		},
	}
	pctx := NewPipelineContext(uuid.New(), "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.True(t, called, "skill-runtime server was not called")
	assert.Equal(t, "done", out["result"])
}

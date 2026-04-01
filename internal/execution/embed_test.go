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

func TestEmbedExecutor_CallsEmbeddingService(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/embed", r.URL.Path)
		assert.Equal(t, http.MethodPost, r.Method)

		var req map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&req))
		assert.Equal(t, "hello world", req["text"])

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"embedding": []float32{0.1, 0.2, 0.3},
		})
	}))
	defer srv.Close()

	exec := &embedExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "embed1",
		Type: "EMBED",
		Config: map[string]any{
			"text": "hello world",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "hello world", out["text"])
	assert.NotNil(t, out["embedding"])
}

func TestEmbedExecutor_UsesUpstreamOutput(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req map[string]string
		_ = json.NewDecoder(r.Body).Decode(&req)
		assert.Equal(t, "upstream text", req["text"])

		_ = json.NewEncoder(w).Encode(map[string]any{"embedding": []float32{0.5}})
	}))
	defer srv.Close()

	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"text": "upstream text"})

	exec := &embedExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "embed2",
		Type: "EMBED",
		Config: map[string]any{
			"inputNodeId": "prev",
		},
	}

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "upstream text", out["text"])
}

func TestEmbedExecutor_MissingText(t *testing.T) {
	exec := &embedExecutor{embeddingURL: "http://localhost:8000"}
	node := &Node{
		ID:     "embed3",
		Type:   "EMBED",
		Config: map[string]any{},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no text to embed")
}

func TestEmbedExecutor_MissingURL(t *testing.T) {
	exec := &embedExecutor{embeddingURL: ""}
	node := &Node{
		ID:     "embed4",
		Type:   "EMBED",
		Config: map[string]any{"text": "hello"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "EMBEDDING_URL not configured")
}

func TestEmbedExecutor_ServiceError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	exec := &embedExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:     "embed5",
		Type:   "EMBED",
		Config: map[string]any{"text": "hello"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "500")
}

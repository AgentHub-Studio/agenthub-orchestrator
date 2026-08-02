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

// --- RETRIEVE ---

func TestRetrieveExecutor_CallsSearchService(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/search", r.URL.Path)
		assert.Equal(t, http.MethodPost, r.Method)

		var req map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&req))
		assert.Equal(t, "kb-123", req["knowledgeBaseId"])
		assert.Equal(t, "what is RAG?", req["query"])

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"results": []any{
				map[string]any{"id": "doc1", "text": "RAG is retrieval augmented generation"},
				map[string]any{"id": "doc2", "text": "It combines retrieval with LLMs"},
			},
		})
	}))
	defer srv.Close()

	exec := &retrieveExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "retrieve1",
		Type: "RETRIEVE",
		Config: map[string]any{
			"knowledgeBaseId": "kb-123",
			"query":           "what is RAG?",
			"topK":            float64(5),
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 2, out["count"])
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 2)
}

func TestRetrieveExecutor_ResolvesQueryFromUpstream(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req map[string]any
		_ = json.NewDecoder(r.Body).Decode(&req)
		assert.Equal(t, "upstream query", req["query"])
		_ = json.NewEncoder(w).Encode(map[string]any{"results": []any{}})
	}))
	defer srv.Close()

	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("llm-node", map[string]any{"message": "upstream query"})

	exec := &retrieveExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "retrieve2",
		Type: "RETRIEVE",
		Config: map[string]any{
			"knowledgeBaseId": "kb-456",
			"inputNodeId":     "llm-node",
		},
	}

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 0, out["count"])
}

func TestRetrieveExecutor_MissingKnowledgeBaseId(t *testing.T) {
	exec := &retrieveExecutor{embeddingURL: "http://localhost:8000"}
	node := &Node{
		ID:     "retrieve3",
		Type:   "RETRIEVE",
		Config: map[string]any{"query": "something"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "knowledgeBaseId")
}

func TestRetrieveExecutor_MissingQuery(t *testing.T) {
	exec := &retrieveExecutor{embeddingURL: "http://localhost:8000"}
	node := &Node{
		ID:     "retrieve4",
		Type:   "RETRIEVE",
		Config: map[string]any{"knowledgeBaseId": "kb-789"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "query")
}

func TestRetrieveExecutor_NoEmbeddingURL_GracefulDegradation(t *testing.T) {
	exec := &retrieveExecutor{embeddingURL: ""}
	node := &Node{
		ID:   "retrieve5",
		Type: "RETRIEVE",
		Config: map[string]any{
			"knowledgeBaseId": "kb-123",
			"query":           "anything",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, 0, out["count"])
}

func TestRetrieveExecutor_ServiceError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	exec := &retrieveExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "retrieve6",
		Type: "RETRIEVE",
		Config: map[string]any{
			"knowledgeBaseId": "kb-123",
			"query":           "test",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "500")
}

// --- RERANK ---

func TestRerankExecutor_CallsRerankService(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/rerank", r.URL.Path)
		assert.Equal(t, http.MethodPost, r.Method)

		var req map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&req))
		assert.Equal(t, "best document", req["query"])
		docs, _ := req["documents"].([]any)
		assert.Len(t, docs, 3)

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"results": []any{
				map[string]any{"id": "doc2", "score": 0.95},
				map[string]any{"id": "doc1", "score": 0.70},
			},
		})
	}))
	defer srv.Close()

	docs := []any{
		map[string]any{"id": "doc1"},
		map[string]any{"id": "doc2"},
		map[string]any{"id": "doc3"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("retrieve-node", map[string]any{"results": docs})

	exec := &rerankExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "rerank1",
		Type: "RERANK",
		Config: map[string]any{
			"query":         "best document",
			"documentsFrom": "retrieve-node",
			"topN":          float64(2),
		},
	}

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 2)
}

func TestRerankExecutor_FallbackWhenServiceUnavailable(t *testing.T) {
	docs := []any{
		map[string]any{"id": "doc1"},
		map[string]any{"id": "doc2"},
		map[string]any{"id": "doc3"},
		map[string]any{"id": "doc4"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("retrieve-node", map[string]any{"results": docs})

	exec := &rerankExecutor{embeddingURL: "http://localhost:1"} // unreachable
	node := &Node{
		ID:   "rerank2",
		Type: "RERANK",
		Config: map[string]any{
			"query":         "test",
			"documentsFrom": "retrieve-node",
			"topN":          float64(2),
		},
	}

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err) // graceful fallback
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 2, "should return first topN docs as fallback")
}

func TestRerankExecutor_FallbackWhenNoEmbeddingURL(t *testing.T) {
	docs := []any{
		map[string]any{"id": "doc1"},
		map[string]any{"id": "doc2"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("src", map[string]any{"results": docs})

	exec := &rerankExecutor{embeddingURL: ""}
	node := &Node{
		ID:   "rerank3",
		Type: "RERANK",
		Config: map[string]any{
			"query":         "test",
			"documentsFrom": "src",
			"topN":          float64(5),
		},
	}

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	results, ok := out["results"].([]any)
	require.True(t, ok)
	assert.Len(t, results, 2, "all docs returned when topN > count")
}

func TestRerankExecutor_MissingQuery(t *testing.T) {
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	exec := &rerankExecutor{embeddingURL: "http://localhost:8000"}
	node := &Node{
		ID:     "rerank4",
		Type:   "RERANK",
		Config: map[string]any{"documentsFrom": "somewhere"},
	}

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "query")
}

func TestRerankExecutor_ServiceReturnsNon200_Fallback(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	docs := []any{map[string]any{"id": "d1"}, map[string]any{"id": "d2"}}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("src", map[string]any{"results": docs})

	exec := &rerankExecutor{embeddingURL: srv.URL}
	node := &Node{
		ID:   "rerank5",
		Type: "RERANK",
		Config: map[string]any{
			"query":         "test",
			"documentsFrom": "src",
			"topN":          float64(1),
		},
	}

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	results := out["results"].([]any)
	assert.Len(t, results, 1, "fallback to first topN")
}

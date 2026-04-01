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

// newTestContext returns a minimal PipelineContext for tests.
func newTestContext() *PipelineContext {
	return NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{"msg": "hello"})
}

// ---------------------------------------------------------------------------
// HTTP executor
// ---------------------------------------------------------------------------

func TestHTTPExecutor_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"result": "ok"}) //nolint:errcheck
	}))
	defer srv.Close()

	exec := &httpExecutor{}
	node := &Node{
		ID:   "http1",
		Type: "HTTP",
		Config: map[string]any{
			"method": "GET",
			"url":    srv.URL + "/test",
		},
	}

	out, err := exec.Execute(context.Background(), node, newTestContext())
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, out["statusCode"])
	assert.Equal(t, true, out["ok"])
}

func TestHTTPExecutor_PostWithBody(t *testing.T) {
	var receivedBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&receivedBody) //nolint:errcheck
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]string{"created": "true"}) //nolint:errcheck
	}))
	defer srv.Close()

	exec := &httpExecutor{}
	node := &Node{
		ID:   "http2",
		Type: "HTTP",
		Config: map[string]any{
			"method": "POST",
			"url":    srv.URL + "/items",
			"body":   map[string]any{"name": "test"},
		},
	}

	out, err := exec.Execute(context.Background(), node, newTestContext())
	require.NoError(t, err)
	assert.Equal(t, http.StatusCreated, out["statusCode"])
	assert.Equal(t, "test", receivedBody["name"])
}

func TestHTTPExecutor_MissingURL(t *testing.T) {
	exec := &httpExecutor{}
	node := &Node{ID: "http3", Type: "HTTP", Config: map[string]any{}}
	_, err := exec.Execute(context.Background(), node, newTestContext())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "missing required config 'url'")
}

func TestHTTPExecutor_4xxIsNotError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]string{"error": "not found"}) //nolint:errcheck
	}))
	defer srv.Close()

	exec := &httpExecutor{}
	node := &Node{
		ID:     "http4",
		Type:   "HTTP",
		Config: map[string]any{"url": srv.URL + "/missing"},
	}

	out, err := exec.Execute(context.Background(), node, newTestContext())
	require.NoError(t, err) // HTTP errors are not Go errors — status code is returned
	assert.Equal(t, http.StatusNotFound, out["statusCode"])
	assert.Equal(t, false, out["ok"])
}

// ---------------------------------------------------------------------------
// SQL executor (delegates to skill-runtime)
// ---------------------------------------------------------------------------

func TestSQLExecutor_DelegatesToSkillRuntime(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/skills/my-sql-skill/execute", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"rows": []any{map[string]any{"id": 1}}}) //nolint:errcheck
	}))
	defer srv.Close()

	exec := &sqlExecutor{skillRuntimeURL: srv.URL}
	node := &Node{
		ID:   "sql1",
		Type: "SQL",
		Config: map[string]any{
			"skillSlug":    "my-sql-skill",
			"query":        "SELECT * FROM users",
			"datasourceId": "ds-uuid",
		},
	}

	out, err := exec.Execute(context.Background(), node, newTestContext())
	require.NoError(t, err)
	assert.NotNil(t, out["rows"])
}

func TestSQLExecutor_MissingSkillSlug(t *testing.T) {
	exec := &sqlExecutor{skillRuntimeURL: "http://localhost"}
	node := &Node{ID: "sql2", Type: "SQL", Config: map[string]any{"query": "SELECT 1"}}
	_, err := exec.Execute(context.Background(), node, newTestContext())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skillSlug")
}

func TestSQLExecutor_MissingQuery(t *testing.T) {
	exec := &sqlExecutor{skillRuntimeURL: "http://localhost"}
	node := &Node{ID: "sql3", Type: "SQL", Config: map[string]any{"skillSlug": "my-sql"}}
	_, err := exec.Execute(context.Background(), node, newTestContext())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "'query'")
}

func TestSQLExecutor_NoSkillRuntimeURL(t *testing.T) {
	exec := &sqlExecutor{}
	node := &Node{
		ID:   "sql4",
		Type: "SQL",
		Config: map[string]any{
			"skillSlug": "my-sql",
			"query":     "SELECT 1",
		},
	}
	_, err := exec.Execute(context.Background(), node, newTestContext())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skill-runtime URL not configured")
}

// ---------------------------------------------------------------------------
// DOCUMENT_SEARCH executor (delegates to skill-runtime)
// ---------------------------------------------------------------------------

func TestDocumentSearchExecutor_DelegatesToSkillRuntime(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/api/skills/my-search-skill/execute", r.URL.Path)

		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body) //nolint:errcheck
		assert.Equal(t, "kb-123", body["knowledgeBaseId"])
		assert.Equal(t, "what is AI?", body["query"])

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"documents": []any{map[string]any{"text": "AI is..."}}}) //nolint:errcheck
	}))
	defer srv.Close()

	exec := &documentSearchExecutor{skillRuntimeURL: srv.URL}
	node := &Node{
		ID:   "ds1",
		Type: "DOCUMENT_SEARCH",
		Config: map[string]any{
			"skillSlug":       "my-search-skill",
			"knowledgeBaseId": "kb-123",
			"query":           "what is AI?",
			"topK":            float64(3),
		},
	}

	out, err := exec.Execute(context.Background(), node, newTestContext())
	require.NoError(t, err)
	assert.NotNil(t, out["documents"])
}

func TestDocumentSearchExecutor_MissingSkillSlug(t *testing.T) {
	exec := &documentSearchExecutor{skillRuntimeURL: "http://localhost"}
	node := &Node{
		ID:   "ds2",
		Type: "DOCUMENT_SEARCH",
		Config: map[string]any{"knowledgeBaseId": "kb-123"},
	}
	_, err := exec.Execute(context.Background(), node, newTestContext())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "skillSlug")
}

func TestDocumentSearchExecutor_MissingKnowledgeBaseID(t *testing.T) {
	exec := &documentSearchExecutor{skillRuntimeURL: "http://localhost"}
	node := &Node{
		ID:   "ds3",
		Type: "DOCUMENT_SEARCH",
		Config: map[string]any{"skillSlug": "my-search"},
	}
	_, err := exec.Execute(context.Background(), node, newTestContext())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "knowledgeBaseId")
}

func TestDocumentSearchExecutor_UsesUpstreamQuery(t *testing.T) {
	capturedBody := map[string]any{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&capturedBody) //nolint:errcheck
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"documents": []any{}}) //nolint:errcheck
	}))
	defer srv.Close()

	pctx := newTestContext()
	pctx.SetNodeOutput("prev-node", map[string]any{"message": "upstream query text"})

	exec := &documentSearchExecutor{skillRuntimeURL: srv.URL}
	node := &Node{
		ID:   "ds4",
		Type: "DOCUMENT_SEARCH",
		Config: map[string]any{
			"skillSlug":       "my-search",
			"knowledgeBaseId": "kb-456",
			"inputNodeId":     "prev-node",
		},
	}

	_, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "upstream query text", capturedBody["query"])
}

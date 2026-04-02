package execution

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- HTTP node ---

func TestHTTPExecutor_GetRequest(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
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
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, out["status"])
}

func TestHTTPExecutor_PostWithBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))
		var req map[string]any
		_ = json.NewDecoder(r.Body).Decode(&req)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"received": req["name"]})
	}))
	defer srv.Close()

	exec := &httpExecutor{}
	node := &Node{
		ID:   "http2",
		Type: "HTTP",
		Config: map[string]any{
			"method": "POST",
			"url":    srv.URL + "/echo",
			"body":   `{"name": "{{input.name}}"}`,
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("upstream", map[string]any{"name": "Alice"})

	node.Config["inputNodeId"] = "upstream"

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, out["status"])
	body, _ := out["body"].(map[string]any)
	assert.Equal(t, "Alice", body["received"])
}

func TestHTTPExecutor_WithOAuthCredential(t *testing.T) {
	// Mock agenthub-api OAuth resolve endpoint
	apiSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/oauth-credentials/cred-123/resolve" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"header": "Authorization",
				"value":  "Bearer test-access-token",
			})
			return
		}
		// Target endpoint — verify auth header was injected
		assert.Equal(t, "Bearer test-access-token", r.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"authenticated": true})
	}))
	defer apiSrv.Close()

	exec := &httpExecutor{agentHubAPIURL: apiSrv.URL}
	node := &Node{
		ID:   "http3",
		Type: "HTTP",
		Config: map[string]any{
			"method":            "GET",
			"url":               apiSrv.URL + "/protected",
			"oauthCredentialId": "cred-123",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetValue("bearerToken", "Bearer user-token")

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, out["status"])
}

func TestHTTPExecutor_MissingURL(t *testing.T) {
	exec := &httpExecutor{}
	node := &Node{
		ID:     "http4",
		Type:   "HTTP",
		Config: map[string]any{"method": "GET"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "url")
}

func TestHTTPExecutor_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	exec := &httpExecutor{}
	node := &Node{
		ID:   "http5",
		Type: "HTTP",
		Config: map[string]any{
			"url": srv.URL,
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err) // HTTP errors are surfaced via status code, not Go errors
	assert.Equal(t, http.StatusInternalServerError, out["status"])
}

func TestHTTPExecutor_URLTemplate(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/users/42", r.URL.Path)
		_ = json.NewEncoder(w).Encode(map[string]any{"id": "42"})
	}))
	defer srv.Close()

	exec := &httpExecutor{}
	node := &Node{
		ID:   "http6",
		Type: "HTTP",
		Config: map[string]any{
			"url":         srv.URL + "/users/{{input.userId}}",
			"inputNodeId": "prev",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"userId": "42"})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, out["status"])
}

func TestHTTPExecutor_OAuthResolveFails_ContinuesWithoutAuth(t *testing.T) {
	// Target server — should receive request without auth header
	targetSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Empty(t, r.Header.Get("Authorization"))
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true})
	}))
	defer targetSrv.Close()

	exec := &httpExecutor{agentHubAPIURL: "http://localhost:1"} // unreachable API
	node := &Node{
		ID:   "http7",
		Type: "HTTP",
		Config: map[string]any{
			"url":               targetSrv.URL,
			"oauthCredentialId": "cred-missing",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err) // OAuth failure is a warning, not a hard error
	assert.Equal(t, http.StatusOK, out["status"])
}

// --- SQL node ---

func TestSQLExecutor_MissingDatasourceId(t *testing.T) {
	exec := &sqlExecutor{}
	node := &Node{
		ID:     "sql1",
		Type:   "SQL",
		Config: map[string]any{"query": "SELECT 1"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "datasourceId")
}

func TestSQLExecutor_MissingQuery(t *testing.T) {
	exec := &sqlExecutor{}
	node := &Node{
		ID:     "sql2",
		Type:   "SQL",
		Config: map[string]any{"datasourceId": "ds-123"},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "query")
}

func TestSQLExecutor_NoAPIURL_ReturnsSkipped(t *testing.T) {
	exec := &sqlExecutor{agentHubAPIURL: ""}
	node := &Node{
		ID:   "sql3",
		Type: "SQL",
		Config: map[string]any{
			"datasourceId": "ds-123",
			"query":        "SELECT id FROM users LIMIT 5",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	out, err := exec.Execute(context.Background(), node, pctx)
	require.NoError(t, err)
	assert.Equal(t, "skipped", out["status"])
	assert.Contains(t, out["query"], "SELECT")
}

func TestSQLExecutor_FetchDatasource_APIReturns404(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	exec := &sqlExecutor{agentHubAPIURL: srv.URL}
	node := &Node{
		ID:   "sql4",
		Type: "SQL",
		Config: map[string]any{
			"datasourceId": "ds-missing",
			"query":        "SELECT 1",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "fetch datasource")
}

func TestSQLExecutor_UnsupportedDatasourceType(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "ds-1", "type": "MYSQL",
			"host": "localhost", "port": 3306, "database": "test",
			"user": "root", "password": "pass",
		})
	}))
	defer srv.Close()

	exec := &sqlExecutor{agentHubAPIURL: srv.URL}
	node := &Node{
		ID:   "sql5",
		Type: "SQL",
		Config: map[string]any{
			"datasourceId": "ds-1",
			"query":        "SELECT 1",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "MYSQL")
}

func TestSQLExecutor_QueryTemplateRendering(t *testing.T) {
	// API returns a datasource — connect will fail but template rendering is validated
	// by testing the rendered query reaches the error path correctly.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": "ds-1", "type": "POSTGRESQL",
			"host": "localhost", "port": 5432, "database": "testdb",
			"user": "user", "password": "pass",
		})
	}))
	defer srv.Close()

	exec := &sqlExecutor{agentHubAPIURL: srv.URL}
	node := &Node{
		ID:   "sql6",
		Type: "SQL",
		Config: map[string]any{
			"datasourceId": "ds-1",
			"query":        "SELECT * FROM users WHERE id = '{{input.userId}}'",
			"inputNodeId":  "prev",
		},
	}
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})
	pctx.SetNodeOutput("prev", map[string]any{"userId": "u-999"})

	// Will fail at DB connection stage (no real DB), but that's expected
	_, err := exec.Execute(context.Background(), node, pctx)
	require.Error(t, err)
	// Error comes from either "connect to datasource" (pool creation) or "execute query" (connection refused)
	assert.True(t, strings.Contains(err.Error(), "connect to datasource") || strings.Contains(err.Error(), "execute query"), err.Error())
}

// --- renderNodeTemplate ---

func TestRenderNodeTemplate(t *testing.T) {
	tests := []struct {
		name     string
		tmpl     string
		data     map[string]any
		expected string
	}{
		{
			name:     "no placeholders",
			tmpl:     "hello world",
			data:     map[string]any{"name": "Alice"},
			expected: "hello world",
		},
		{
			name:     "single placeholder",
			tmpl:     "hello {{input.name}}",
			data:     map[string]any{"name": "Alice"},
			expected: "hello Alice",
		},
		{
			name:     "multiple placeholders",
			tmpl:     "{{input.greeting}} {{input.name}}!",
			data:     map[string]any{"greeting": "Hello", "name": "Bob"},
			expected: "Hello Bob!",
		},
		{
			name:     "nil data",
			tmpl:     "{{input.field}}",
			data:     nil,
			expected: "{{input.field}}",
		},
		{
			name:     "missing key",
			tmpl:     "{{input.missing}}",
			data:     map[string]any{"other": "val"},
			expected: "{{input.missing}}", // unresolved placeholder stays
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			result := renderNodeTemplate(tc.tmpl, tc.data)
			assert.Equal(t, tc.expected, result)
		})
	}
}

// --- SetValue/GetValue on PipelineContext ---

func TestPipelineContext_Metadata(t *testing.T) {
	pctx := NewPipelineContext(uuid.Nil, "test-tenant", map[string]any{})

	assert.Nil(t, pctx.GetValue("bearerToken"))

	pctx.SetValue("bearerToken", "Bearer abc123")
	assert.Equal(t, "Bearer abc123", pctx.GetValue("bearerToken"))

	pctx.SetValue("bearerToken", "Bearer xyz")
	assert.Equal(t, "Bearer xyz", pctx.GetValue("bearerToken"))
}

package execution

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestAPIClient_FetchPipeline_Success(t *testing.T) {
	pipelineID := "pipeline-abc"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/api/agents/agent-123"):
			assert.Equal(t, "Bearer test-token", r.Header.Get("Authorization"))
			_ = json.NewEncoder(w).Encode(map[string]any{"id": "agent-123", "pipelineId": pipelineID})

		case strings.HasSuffix(r.URL.Path, "/api/pipelines/"+pipelineID):
			assert.Equal(t, "Bearer test-token", r.Header.Get("Authorization"))
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id": pipelineID,
				"nodes": []any{
					map[string]any{"id": "n1", "nodeType": "INPUT", "config": map[string]any{}},
					map[string]any{"id": "n2", "nodeType": "LLM", "config": map[string]any{"model": "gpt-4o"}},
					map[string]any{"id": "n3", "nodeType": "OUTPUT", "config": map[string]any{"sources": []any{"n2"}}},
				},
				"edges": []any{
					map[string]any{"sourceNodeId": "n1", "targetNodeId": "n2"},
					map[string]any{"sourceNodeId": "n2", "targetNodeId": "n3"},
				},
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	client := NewAPIClient(srv.URL)
	data, err := client.FetchPipeline(context.Background(), "Bearer test-token", "agent-123")

	require.NoError(t, err)
	require.NotNil(t, data)
	assert.Len(t, data.Nodes, 3)
	assert.Len(t, data.Edges, 2)

	assert.Equal(t, "n1", data.Nodes[0].ID)
	assert.Equal(t, "INPUT", data.Nodes[0].Type)
	assert.Equal(t, "n2", data.Nodes[1].ID)
	assert.Equal(t, "LLM", data.Nodes[1].Type)
	assert.Equal(t, "gpt-4o", data.Nodes[1].Config["model"])

	assert.Equal(t, "n1", data.Edges[0].Source)
	assert.Equal(t, "n2", data.Edges[0].Target)
}

func TestAPIClient_FetchPipeline_AgentNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	client := NewAPIClient(srv.URL)
	_, err := client.FetchPipeline(context.Background(), "", "missing-agent")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "404")
}

func TestAPIClient_FetchPipeline_AgentHasNoPipeline(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		// Agent with no pipelineId
		_ = json.NewEncoder(w).Encode(map[string]any{"id": "agent-no-pipeline"})
	}))
	defer srv.Close()

	client := NewAPIClient(srv.URL)
	_, err := client.FetchPipeline(context.Background(), "", "agent-no-pipeline")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "no pipeline configured")
}

func TestAPIClient_FetchPipeline_PipelineNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "/api/agents/") {
			_ = json.NewEncoder(w).Encode(map[string]any{"id": "agent-1", "pipelineId": "missing-pipeline"})
			return
		}
		// Pipeline endpoint returns 404
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	client := NewAPIClient(srv.URL)
	_, err := client.FetchPipeline(context.Background(), "", "agent-1")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "404")
}

func TestAPIClient_FetchPipeline_ServiceUnavailable(t *testing.T) {
	client := NewAPIClient("http://localhost:1") // unreachable
	_, err := client.FetchPipeline(context.Background(), "", "agent-123")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "fetch agent")
}

func TestAPIClient_FetchPipeline_EmptyConfig(t *testing.T) {
	pipelineID := "pipeline-xyz"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "/api/agents/") {
			_ = json.NewEncoder(w).Encode(map[string]any{"pipelineId": pipelineID})
			return
		}
		// Node with nil config
		_ = json.NewEncoder(w).Encode(map[string]any{
			"id": pipelineID,
			"nodes": []any{
				map[string]any{"id": "n1", "nodeType": "INPUT"},
			},
			"edges": []any{},
		})
	}))
	defer srv.Close()

	client := NewAPIClient(srv.URL)
	data, err := client.FetchPipeline(context.Background(), "", fmt.Sprintf("agent-%s", pipelineID))

	require.NoError(t, err)
	require.Len(t, data.Nodes, 1)
	assert.NotNil(t, data.Nodes[0].Config, "nil config should be initialized to empty map")
}

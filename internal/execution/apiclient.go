package execution

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// PipelineData holds pipeline nodes and edges fetched from agenthub-api.
type PipelineData struct {
	Nodes []*Node
	Edges []Edge
}

// apiAgentResponse is the subset of the agenthub-api agent JSON we need.
type apiAgentResponse struct {
	PipelineID *string `json:"pipelineId"`
}

// apiNodeResponse is the subset of the agenthub-api pipeline node JSON we need.
type apiNodeResponse struct {
	ID       string         `json:"id"`
	NodeType string         `json:"nodeType"`
	Name     string         `json:"name"`
	Config   map[string]any `json:"config"`
}

// apiEdgeResponse is the subset of the agenthub-api pipeline edge JSON we need.
type apiEdgeResponse struct {
	SourceNodeID string `json:"sourceNodeId"`
	TargetNodeID string `json:"targetNodeId"`
}

// apiPipelineResponse is the subset of the agenthub-api pipeline JSON we need.
type apiPipelineResponse struct {
	Nodes []apiNodeResponse `json:"nodes"`
	Edges []apiEdgeResponse `json:"edges"`
}

// APIClient fetches agent pipeline definitions from agenthub-api.
type APIClient struct {
	baseURL    string
	httpClient *http.Client
}

// NewAPIClient creates a new APIClient targeting baseURL.
func NewAPIClient(baseURL string) *APIClient {
	return &APIClient{
		baseURL:    baseURL,
		httpClient: &http.Client{Timeout: 15 * time.Second},
	}
}

// FetchPipeline fetches the pipeline for agentID from agenthub-api.
// bearerToken is forwarded as Authorization header for multi-tenant access.
// It first fetches the agent to get its pipelineId, then fetches the pipeline.
func (c *APIClient) FetchPipeline(ctx context.Context, bearerToken, agentID string) (*PipelineData, error) {
	// Step 1: fetch agent to resolve pipelineId.
	agentURL := fmt.Sprintf("%s/api/agents/%s", c.baseURL, agentID)
	agentReq, err := http.NewRequestWithContext(ctx, http.MethodGet, agentURL, nil)
	if err != nil {
		return nil, fmt.Errorf("apiclient: build agent request: %w", err)
	}
	if bearerToken != "" {
		agentReq.Header.Set("Authorization", bearerToken)
	}

	agentResp, err := c.httpClient.Do(agentReq)
	if err != nil {
		return nil, fmt.Errorf("apiclient: fetch agent: %w", err)
	}
	defer agentResp.Body.Close() //nolint:errcheck

	if agentResp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("apiclient: fetch agent: unexpected status %d", agentResp.StatusCode)
	}

	var agent apiAgentResponse
	if err := json.NewDecoder(agentResp.Body).Decode(&agent); err != nil {
		return nil, fmt.Errorf("apiclient: decode agent: %w", err)
	}

	if agent.PipelineID == nil || *agent.PipelineID == "" {
		return nil, fmt.Errorf("apiclient: agent %s has no pipeline configured", agentID)
	}

	// Step 2: fetch pipeline by id.
	pipelineURL := fmt.Sprintf("%s/api/pipelines/%s", c.baseURL, *agent.PipelineID)
	pipelineReq, err := http.NewRequestWithContext(ctx, http.MethodGet, pipelineURL, nil)
	if err != nil {
		return nil, fmt.Errorf("apiclient: build pipeline request: %w", err)
	}
	if bearerToken != "" {
		pipelineReq.Header.Set("Authorization", bearerToken)
	}

	pipelineResp, err := c.httpClient.Do(pipelineReq)
	if err != nil {
		return nil, fmt.Errorf("apiclient: fetch pipeline: %w", err)
	}
	defer pipelineResp.Body.Close() //nolint:errcheck

	if pipelineResp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("apiclient: fetch pipeline: unexpected status %d", pipelineResp.StatusCode)
	}

	var pipeline apiPipelineResponse
	if err := json.NewDecoder(pipelineResp.Body).Decode(&pipeline); err != nil {
		return nil, fmt.Errorf("apiclient: decode pipeline: %w", err)
	}

	return mapPipelineData(pipeline), nil
}

// mapPipelineData converts the API response into orchestrator domain types.
func mapPipelineData(p apiPipelineResponse) *PipelineData {
	nodes := make([]*Node, len(p.Nodes))
	for i, n := range p.Nodes {
		cfg := n.Config
		if cfg == nil {
			cfg = map[string]any{}
		}
		nodes[i] = &Node{
			ID:     n.ID,
			Type:   n.NodeType,
			Config: cfg,
		}
	}

	edges := make([]Edge, len(p.Edges))
	for i, e := range p.Edges {
		edges[i] = Edge{
			Source: e.SourceNodeID,
			Target: e.TargetNodeID,
		}
	}

	return &PipelineData{Nodes: nodes, Edges: edges}
}

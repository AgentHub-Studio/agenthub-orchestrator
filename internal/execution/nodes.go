package execution

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/expr-lang/expr"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
)

// defaultHTTPClient is used by nodes that make outbound HTTP calls.
var defaultHTTPClient = &http.Client{Timeout: 30 * time.Second}

func newHTTPRequest(ctx context.Context, method, url string, body []byte) (*http.Request, error) {
	return http.NewRequestWithContext(ctx, method, url, bytes.NewReader(body))
}

// NodeExecutor processes a single pipeline node.
type NodeExecutor interface {
	Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error)
}

// NodeRegistry holds all node executors and provides lookup by type.
type NodeRegistry struct {
	executors map[string]NodeExecutor
}

// NewNodeRegistry creates a NodeRegistry with all built-in executors.
// providerRegistry may be nil when LLM nodes are not required.
// skillRuntimeURL is the base URL of the skill-runtime service for TOOL node delegation.
// agentHubAPIURL is the base URL of agenthub-api (for OAuth credential and datasource resolution).
func NewNodeRegistry(providerRegistry *ai.ProviderRegistry, skillRuntimeURL string, embeddingURL string, agentHubAPIURL string) *NodeRegistry {
	r := &NodeRegistry{executors: make(map[string]NodeExecutor)}
	// Basic
	r.executors["INPUT"] = &inputExecutor{}
	r.executors["OUTPUT"] = &outputExecutor{}
	r.executors["TRANSFORM"] = &transformExecutor{}
	// AI
	r.executors["LLM"] = &llmExecutor{registry: providerRegistry}
	r.executors["EMBED"] = &embedExecutor{embeddingURL: embeddingURL}
	r.executors["RETRIEVE"] = &retrieveExecutor{embeddingURL: embeddingURL}
	r.executors["RERANK"] = &rerankExecutor{embeddingURL: embeddingURL}
	// Data
	r.executors["SQL"] = &sqlExecutor{agentHubAPIURL: agentHubAPIURL}
	r.executors["HTTP"] = &httpExecutor{agentHubAPIURL: agentHubAPIURL}
	r.executors["DOCUMENT_SEARCH"] = &documentSearchExecutor{}
	r.executors["TOOL"] = &toolExecutor{skillRuntimeURL: skillRuntimeURL}
	// Control
	r.executors["IF"] = &ifExecutor{}
	r.executors["FOREACH"] = &foreachExecutor{}
	r.executors["SWITCH"] = &switchExecutor{}
	r.executors["MERGE"] = &mergeExecutor{}
	r.executors["JOIN"] = &joinExecutor{}
	r.executors["WEBHOOK_OUT"] = &webhookOutExecutor{}
	r.executors["APPROVAL"] = &approvalExecutor{}
	return r
}

// Get returns the executor for nodeType or an error if not found.
func (r *NodeRegistry) Get(nodeType string) (NodeExecutor, error) {
	e, ok := r.executors[nodeType]
	if !ok {
		return nil, fmt.Errorf("nodes: no executor for type %q", nodeType)
	}
	return e, nil
}

// --- INPUT ---

type inputExecutor struct{}

func (e *inputExecutor) Execute(_ context.Context, _ *Node, pctx *PipelineContext) (map[string]any, error) {
	return pctx.Input, nil
}

// --- OUTPUT ---

type outputExecutor struct{}

func (e *outputExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	sources, _ := node.Config["sources"].([]any)
	result := map[string]any{}
	for _, s := range sources {
		nodeID, _ := s.(string)
		if out, ok := pctx.GetNodeOutput(nodeID); ok {
			for k, v := range out {
				result[k] = v
			}
		}
	}
	return result, nil
}

// --- TRANSFORM ---

type transformExecutor struct{}

func (e *transformExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	mappings, _ := node.Config["mappings"].(map[string]any)
	result := map[string]any{}
	if mappings == nil {
		for _, out := range pctx.Snapshot() {
			for k, v := range out {
				result[k] = v
			}
		}
		return result, nil
	}
	for outKey, ref := range mappings {
		refStr, _ := ref.(string)
		result[outKey] = refStr
	}
	return result, nil
}

// --- LLM ---

type llmExecutor struct {
	registry *ai.ProviderRegistry
}

func (e *llmExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	systemPrompt, _ := node.Config["systemPrompt"].(string)
	modelName, _ := node.Config["model"].(string)
	providerName, _ := node.Config["provider"].(string)
	inputNodeID, _ := node.Config["inputNodeId"].(string)

	var userMessage string
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if msg, ok := out["message"].(string); ok {
				userMessage = msg
			} else {
				b, _ := json.Marshal(out)
				userMessage = string(b)
			}
		}
	}
	if userMessage == "" {
		userMessage, _ = node.Config["prompt"].(string)
	}

	slog.Debug("llm node executing", "nodeId", node.ID, "provider", providerName, "model", modelName)

	if e.registry == nil {
		return nil, fmt.Errorf("llm node: no AI provider registry configured")
	}

	var model ai.ChatModel
	var err error
	if providerName != "" {
		model, err = e.registry.GetDefault(providerName)
	} else {
		names := e.registry.Available()
		if len(names) == 0 {
			return nil, fmt.Errorf("llm node: no AI providers registered")
		}
		model, err = e.registry.Get(names[0])
	}
	if err != nil {
		return nil, fmt.Errorf("llm node: %w", err)
	}

	messages := []ai.Message{}
	if systemPrompt != "" {
		messages = append(messages, ai.Message{Role: ai.RoleSystem, Content: systemPrompt})
	}
	messages = append(messages, ai.Message{Role: ai.RoleUser, Content: userMessage})

	resp, err := model.Chat(ctx, messages, ai.ChatOptions{Model: modelName})
	if err != nil {
		return nil, fmt.Errorf("llm node: chat error: %w", err)
	}

	return map[string]any{
		"response":     resp.Content,
		"model":        resp.Model,
		"finishReason": resp.FinishReason,
		"usage": map[string]any{
			"promptTokens":     resp.Usage.PromptTokens,
			"completionTokens": resp.Usage.CompletionTokens,
			"totalTokens":      resp.Usage.TotalTokens,
		},
	}, nil
}

// --- EMBED ---

type embedExecutor struct {
	embeddingURL string
}

func (e *embedExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	field, _ := node.Config["field"].(string)
	if field == "" {
		field = "text"
	}

	var text string
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if v, ok := out[field].(string); ok {
				text = v
			} else {
				b, _ := json.Marshal(out)
				text = string(b)
			}
		}
	}
	if text == "" {
		text, _ = node.Config["text"].(string)
	}
	if text == "" {
		return nil, fmt.Errorf("embed node: no text to embed (set 'text' config or 'inputNodeId')")
	}

	if e.embeddingURL == "" {
		return nil, fmt.Errorf("embed node: EMBEDDING_URL not configured")
	}

	// Call agenthub-embedding service: POST /embed {"text": "..."}
	body, _ := json.Marshal(map[string]string{"text": text})
	req, err := newHTTPRequest(ctx, http.MethodPost, e.embeddingURL+"/embed", body)
	if err != nil {
		return nil, fmt.Errorf("embed node: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("embed node: call embedding service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("embed node: embedding service returned %d", resp.StatusCode)
	}

	var result struct {
		Embedding []float32 `json:"embedding"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("embed node: decode response: %w", err)
	}

	return map[string]any{
		"text":      text,
		"embedding": result.Embedding,
	}, nil
}

// --- SQL ---

// sqlExecutor executes a SQL query against a datasource registered in the tenant schema.
//
// Config fields:
//   - datasourceId: UUID of the data_source row in the tenant schema
//   - query: SQL query (may use {{input.field}} templates)
//   - maxRows: maximum rows to return (default 100)
//   - inputNodeId: optional upstream node whose output populates template variables
type sqlExecutor struct {
	agentHubAPIURL string
}

// sqlDatasourceResponse mirrors the datasource response from agenthub-api /api/proxy/datasources/{id}.
type sqlDatasourceResponse struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Type     string `json:"type"`
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Database string `json:"database"`
	User     string `json:"user"`
	Password string `json:"password"`
}

func (e *sqlExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	datasourceID, _ := node.Config["datasourceId"].(string)
	query, _ := node.Config["query"].(string)
	if datasourceID == "" {
		return nil, fmt.Errorf("sql node: missing required config 'datasourceId'")
	}
	if query == "" {
		return nil, fmt.Errorf("sql node: missing required config 'query'")
	}

	maxRows := 100
	if mr, ok := node.Config["maxRows"].(float64); ok && mr > 0 {
		maxRows = int(mr)
	}

	// Resolve input for template substitution.
	var input map[string]any
	if inputNodeID, _ := node.Config["inputNodeId"].(string); inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			input = out
		}
	}
	renderedQuery := renderNodeTemplate(query, input)

	if e.agentHubAPIURL == "" {
		slog.Warn("sql node: agentHubAPIURL not configured, returning query preview")
		return map[string]any{"query": renderedQuery, "status": "skipped"}, nil
	}

	// Fetch datasource credentials from agenthub-api (proxy credentials endpoint).
	ds, err := e.fetchDatasource(ctx, pctx, datasourceID)
	if err != nil {
		return nil, fmt.Errorf("sql node: fetch datasource: %w", err)
	}

	// Build DSN — only PostgreSQL is supported in the orchestrator node.
	if ds.Type != "POSTGRESQL" {
		return nil, fmt.Errorf("sql node: unsupported datasource type %q; only POSTGRESQL is supported", ds.Type)
	}

	dsn := fmt.Sprintf("postgres://%s:%s@%s:%d/%s?sslmode=disable", ds.User, ds.Password, ds.Host, ds.Port, ds.Database)
	pool, err := connectSQL(ctx, dsn)
	if err != nil {
		return nil, fmt.Errorf("sql node: connect to datasource: %w", err)
	}
	defer pool.Close()

	rows, err := pool.Query(ctx, renderedQuery)
	if err != nil {
		return nil, fmt.Errorf("sql node: execute query: %w", err)
	}
	defer rows.Close()

	fields := rows.FieldDescriptions()
	colNames := make([]string, len(fields))
	for i, f := range fields {
		colNames[i] = string(f.Name)
	}

	var results []map[string]any
	for rows.Next() && len(results) < maxRows {
		vals, err := rows.Values()
		if err != nil {
			return nil, fmt.Errorf("sql node: scan row: %w", err)
		}
		row := make(map[string]any, len(colNames))
		for i, col := range colNames {
			row[col] = vals[i]
		}
		results = append(results, row)
	}

	return map[string]any{
		"rows":    results,
		"count":   len(results),
		"columns": colNames,
	}, nil
}

func (e *sqlExecutor) fetchDatasource(ctx context.Context, pctx *PipelineContext, datasourceID string) (*sqlDatasourceResponse, error) {
	reqURL := e.agentHubAPIURL + "/api/proxy/datasources/" + datasourceID
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, err
	}
	if token := pctx.GetValue("bearerToken"); token != nil {
		req.Header.Set("Authorization", fmt.Sprintf("%v", token))
	}
	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var ds sqlDatasourceResponse
	if err := json.NewDecoder(resp.Body).Decode(&ds); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &ds, nil
}

// oauthResolveResponse mirrors GET /api/oauth-credentials/{id}/resolve response.
type oauthResolveResponse struct {
	Header string `json:"header"`
	Value  string `json:"value"`
}

// --- HTTP ---

// httpExecutor makes outbound HTTP calls, optionally using OAuth credentials
// stored in agenthub-api.
//
// Config fields:
//   - url: target URL (supports {{input.field}} templates)
//   - method: HTTP method (default GET)
//   - headers: map[string]string of extra headers
//   - body: request body string (supports {{input.field}} templates)
//   - oauthCredentialId: UUID of an oauth_credential to use for auth
//   - inputNodeId: optional upstream node whose output populates template variables
type httpExecutor struct {
	agentHubAPIURL string
}

func (e *httpExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	method, _ := node.Config["method"].(string)
	if method == "" {
		method = http.MethodGet
	}
	rawURL, _ := node.Config["url"].(string)
	if rawURL == "" {
		return nil, fmt.Errorf("http node: missing required config 'url'")
	}

	// Resolve input for template substitution.
	var input map[string]any
	if inputNodeID, _ := node.Config["inputNodeId"].(string); inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			input = out
		}
	}

	targetURL := renderNodeTemplate(rawURL, input)

	var bodyBytes []byte
	if bodyTpl, _ := node.Config["body"].(string); bodyTpl != "" {
		rendered := renderNodeTemplate(bodyTpl, input)
		bodyBytes = []byte(rendered)
	}

	req, err := http.NewRequestWithContext(ctx, method, targetURL, bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, fmt.Errorf("http node: build request: %w", err)
	}
	if len(bodyBytes) > 0 {
		req.Header.Set("Content-Type", "application/json")
	}

	// Apply extra headers from config.
	if hdrs, ok := node.Config["headers"].(map[string]any); ok {
		for k, v := range hdrs {
			req.Header.Set(k, renderNodeTemplate(fmt.Sprintf("%v", v), input))
		}
	}

	// Resolve OAuth credential if specified.
	if oauthID, _ := node.Config["oauthCredentialId"].(string); oauthID != "" && e.agentHubAPIURL != "" {
		if authHeader, err := e.resolveOAuth(ctx, pctx, oauthID); err == nil {
			req.Header.Set(authHeader.Header, authHeader.Value)
		} else {
			slog.Warn("http node: could not resolve oauth credential", "id", oauthID, "err", err)
		}
	}

	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("http node: request failed: %w", err)
	}
	defer resp.Body.Close()

	var body any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		body = nil // non-JSON response is OK
	}

	return map[string]any{
		"status":     resp.StatusCode,
		"statusText": resp.Status,
		"body":       body,
	}, nil
}

func (e *httpExecutor) resolveOAuth(ctx context.Context, pctx *PipelineContext, credentialID string) (*oauthResolveResponse, error) {
	reqURL := e.agentHubAPIURL + "/api/oauth-credentials/" + credentialID + "/resolve"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, err
	}
	if token := pctx.GetValue("bearerToken"); token != nil {
		req.Header.Set("Authorization", fmt.Sprintf("%v", token))
	}
	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var authResp oauthResolveResponse
	if err := json.NewDecoder(resp.Body).Decode(&authResp); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &authResp, nil
}

// renderNodeTemplate replaces {{input.field}} placeholders in s with values from data.
func renderNodeTemplate(s string, data map[string]any) string {
	if data == nil || !bytes.ContainsAny([]byte(s), "{}") {
		return s
	}
	result := s
	for k, v := range data {
		placeholder := "{{input." + k + "}}"
		result = strings.ReplaceAll(result, placeholder, fmt.Sprintf("%v", v))
	}
	return result
}

// --- DOCUMENT_SEARCH ---

type documentSearchExecutor struct{}

func (e *documentSearchExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	knowledgeBaseID, _ := node.Config["knowledgeBaseId"].(string)
	if knowledgeBaseID == "" {
		return nil, fmt.Errorf("document_search node: missing required config 'knowledgeBaseId'")
	}
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	var query string
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if q, ok := out["query"].(string); ok {
				query = q
			} else if q, ok := out["message"].(string); ok {
				query = q
			}
		}
	}
	if query == "" {
		query, _ = node.Config["query"].(string)
	}
	topK, _ := node.Config["topK"].(float64)
	if topK == 0 {
		topK = 5
	}
	return map[string]any{
		"knowledgeBaseId": knowledgeBaseID,
		"query":           query,
		"topK":            int(topK),
		"documents":       []any{},
	}, nil
}

// --- TOOL ---

type toolExecutor struct {
	skillRuntimeURL string
}

func (e *toolExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	skillSlug, _ := node.Config["skillSlug"].(string)
	if skillSlug == "" {
		return nil, fmt.Errorf("tool node: missing required config 'skillSlug'")
	}
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	var input map[string]any
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			input = out
		}
	}
	if e.skillRuntimeURL == "" {
		return nil, fmt.Errorf("tool node: skill-runtime URL not configured")
	}
	payload := map[string]any{"input": input}
	body, _ := json.Marshal(payload)
	req, err := newHTTPRequest(ctx, "POST", e.skillRuntimeURL+"/api/v1/skills/"+skillSlug+"/execute", body)
	if err != nil {
		return nil, fmt.Errorf("tool node: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tool node: skill-runtime request: %w", err)
	}
	defer resp.Body.Close()
	var result map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("tool node: decode response: %w", err)
	}
	return result, nil
}

// --- IF ---

type ifExecutor struct{}

// Execute evaluates the condition expression using expr-lang/expr against the upstream node output.
// The condition may reference upstream fields directly (e.g. "score > 0.8", "status == 'active'").
// Returns branch="true" or branch="false".
func (e *ifExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	condition, _ := node.Config["condition"].(string)
	if condition == "" {
		return map[string]any{"condition": "", "branch": "true"}, nil
	}

	env := map[string]any{}
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			env = out
		}
	}

	result, err := evalBoolExpr(condition, env)
	if err != nil {
		return nil, fmt.Errorf("if node: expression eval: %w", err)
	}
	branch := "false"
	if result {
		branch = "true"
	}
	return map[string]any{"condition": condition, "branch": branch}, nil
}

// evalBoolExpr evaluates an expr-lang/expr expression that must return bool.
// env contains the upstream node output fields as variables.
func evalBoolExpr(expression string, env map[string]any) (bool, error) {
	program, err := expr.Compile(expression, expr.Env(env), expr.AsBool())
	if err != nil {
		// Fallback: try without type assertion (expr may infer non-bool)
		program, err = expr.Compile(expression, expr.Env(env))
		if err != nil {
			return false, fmt.Errorf("compile %q: %w", expression, err)
		}
	}
	out, err := expr.Run(program, env)
	if err != nil {
		return false, fmt.Errorf("eval %q: %w", expression, err)
	}
	b, ok := out.(bool)
	if !ok {
		return false, fmt.Errorf("expression %q did not return bool (got %T)", expression, out)
	}
	return b, nil
}

// --- FOREACH ---

// defaultForeachConcurrency is the default max parallel workers for FOREACH.
const defaultForeachConcurrency = 4

type foreachExecutor struct{}

// Execute iterates over items from the upstream node output in parallel using a worker pool.
// Config: inputNodeId (string), itemsKey (string, default "items"), concurrency (int, default 4).
// Each item is processed independently; results collected in order.
func (e *foreachExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	itemsKey, _ := node.Config["itemsKey"].(string)
	if itemsKey == "" {
		itemsKey = "items"
	}
	concurrency := defaultForeachConcurrency
	if v, ok := node.Config["concurrency"].(int); ok && v > 0 {
		concurrency = v
	}

	var items []any
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if v, ok := out[itemsKey].([]any); ok {
				items = v
			}
		}
	}

	if len(items) == 0 {
		return map[string]any{"items": []any{}, "count": 0, "results": []any{}}, nil
	}

	results := make([]any, len(items))
	sem := make(chan struct{}, concurrency)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var firstErr error

	for i, item := range items {
		wg.Add(1)
		sem <- struct{}{}
		go func(idx int, it any) {
			defer wg.Done()
			defer func() { <-sem }()
			mu.Lock()
			results[idx] = map[string]any{"index": idx, "item": it}
			mu.Unlock()
		}(i, item)
	}
	wg.Wait()

	if firstErr != nil {
		return nil, firstErr
	}
	return map[string]any{"items": items, "count": len(items), "results": results}, nil
}

// --- SWITCH ---

type switchExecutor struct{}

// Execute evaluates the expression using expr-lang/expr against upstream output and routes to the matching branch.
// Config: inputNodeId (string), expression (string) — evaluated to a string value, cases (map[string]string), default (string).
func (e *switchExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	expression, _ := node.Config["expression"].(string)
	cases, _ := node.Config["cases"].(map[string]any)
	defaultBranch, _ := node.Config["default"].(string)
	if defaultBranch == "" {
		defaultBranch = "default"
	}

	env := map[string]any{}
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			env = out
		}
	}

	var value string
	if expression != "" {
		program, err := expr.Compile(expression, expr.Env(env))
		if err != nil {
			return nil, fmt.Errorf("switch node: compile expression %q: %w", expression, err)
		}
		out, err := expr.Run(program, env)
		if err != nil {
			return nil, fmt.Errorf("switch node: eval expression %q: %w", expression, err)
		}
		value = fmt.Sprintf("%v", out)
	}

	branch := defaultBranch
	if cases != nil {
		if b, ok := cases[value].(string); ok {
			branch = b
		}
	}
	return map[string]any{"value": value, "branch": branch}, nil
}

// --- MERGE ---

// MergeStrategy controls how outputs from multiple sources are combined.
type MergeStrategy string

const (
	MergeStrategyAll    MergeStrategy = "MERGE_ALL" // merge all keys (last wins on conflict)
	MergeStrategyFirst  MergeStrategy = "FIRST"     // keep only the first source's output
	MergeStrategyLast   MergeStrategy = "LAST"      // keep only the last source's output
	MergeStrategyConcat MergeStrategy = "CONCAT"    // collect all outputs into a "results" array
)

type mergeExecutor struct{}

// Execute merges outputs from multiple source nodes using the configured strategy.
// Config: sources ([]string), strategy (string: MERGE_ALL|FIRST|LAST|CONCAT, default MERGE_ALL).
func (e *mergeExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	sources, _ := node.Config["sources"].([]any)
	strategy := MergeStrategyAll
	if s, ok := node.Config["strategy"].(string); ok && s != "" {
		strategy = MergeStrategy(s)
	}

	var collected []map[string]any
	for _, s := range sources {
		nodeID, _ := s.(string)
		if out, ok := pctx.GetNodeOutput(nodeID); ok {
			collected = append(collected, out)
		}
	}

	switch strategy {
	case MergeStrategyFirst:
		if len(collected) == 0 {
			return map[string]any{}, nil
		}
		return collected[0], nil

	case MergeStrategyLast:
		if len(collected) == 0 {
			return map[string]any{}, nil
		}
		return collected[len(collected)-1], nil

	case MergeStrategyConcat:
		items := make([]any, len(collected))
		for i, m := range collected {
			items[i] = m
		}
		return map[string]any{"results": items, "count": len(items)}, nil

	default: // MERGE_ALL
		result := map[string]any{}
		for _, m := range collected {
			for k, v := range m {
				result[k] = v
			}
		}
		return result, nil
	}
}

// --- JOIN ---

type joinExecutor struct{}

func (e *joinExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	sources, _ := node.Config["sources"].([]any)
	results := make([]any, 0, len(sources))
	for _, s := range sources {
		nodeID, _ := s.(string)
		if out, ok := pctx.GetNodeOutput(nodeID); ok {
			results = append(results, out)
		}
	}
	return map[string]any{"results": results, "count": len(results)}, nil
}

// --- WEBHOOK_OUT ---

type webhookOutExecutor struct{}

func (e *webhookOutExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	webhookURL, _ := node.Config["url"].(string)
	if webhookURL == "" {
		return nil, fmt.Errorf("webhook_out node: missing required config 'url'")
	}
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	var payload map[string]any
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			payload = out
		}
	}
	body, _ := json.Marshal(payload)
	req, err := newHTTPRequest(ctx, "POST", webhookURL, body)
	if err != nil {
		return nil, fmt.Errorf("webhook_out node: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("webhook_out node: request failed: %w", err)
	}
	defer resp.Body.Close()
	slog.Info("webhook_out delivered", "nodeId", node.ID, "url", webhookURL, "status", resp.StatusCode)
	return map[string]any{"status": resp.StatusCode, "delivered": true}, nil
}

// --- APPROVAL ---

type approvalExecutor struct{}

func (e *approvalExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	approvalID := fmt.Sprintf("approval-%s-%s", pctx.ExecutionID, node.ID)
	message, _ := node.Config["message"].(string)
	if message == "" {
		message = "Approval required to continue"
	}
	return map[string]any{
		"approvalId": approvalID,
		"message":    message,
		"status":     "pending_approval",
	}, nil
}

// --- RETRIEVE ---

// retrieveExecutor fetches documents from a knowledge base using vector similarity search.
// It calls the embedding service's search endpoint: POST {embeddingURL}/search.
// Config keys: knowledgeBaseId (string), query (string), topK (int, default 5).
// The query may also be resolved from an upstream node via inputNodeId config.
type retrieveExecutor struct {
	embeddingURL string
}

func (e *retrieveExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	knowledgeBaseID, _ := node.Config["knowledgeBaseId"].(string)
	if knowledgeBaseID == "" {
		return nil, fmt.Errorf("retrieve node: missing required config 'knowledgeBaseId'")
	}

	// Resolve query from upstream node output or direct config.
	var query string
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if q, ok := out["query"].(string); ok {
				query = q
			} else if q, ok := out["message"].(string); ok {
				query = q
			}
		}
	}
	if query == "" {
		query, _ = node.Config["query"].(string)
	}
	if query == "" {
		return nil, fmt.Errorf("retrieve node: missing required config 'query' (or set 'inputNodeId')")
	}

	topK := 5
	if v, ok := node.Config["topK"].(float64); ok && v > 0 {
		topK = int(v)
	}

	if e.embeddingURL == "" {
		// Graceful degradation: return empty results when embedding service is not configured.
		slog.Warn("retrieve node: EMBEDDING_URL not configured, returning empty results", "nodeId", node.ID)
		return map[string]any{
			"results": []any{},
			"count":   0,
		}, nil
	}

	payload, _ := json.Marshal(map[string]any{
		"knowledgeBaseId": knowledgeBaseID,
		"query":           query,
		"topK":            topK,
	})
	req, err := newHTTPRequest(ctx, http.MethodPost, e.embeddingURL+"/search", payload)
	if err != nil {
		return nil, fmt.Errorf("retrieve node: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("retrieve node: call embedding service: %w", err)
	}
	defer resp.Body.Close() //nolint:errcheck

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("retrieve node: embedding service returned %d", resp.StatusCode)
	}

	var result struct {
		Results []any `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("retrieve node: decode response: %w", err)
	}

	return map[string]any{
		"results": result.Results,
		"count":   len(result.Results),
	}, nil
}

// --- RERANK ---

// rerankExecutor re-ranks a list of documents/results based on a query using the reranking model.
// Config keys: query (string), documentsFrom (string — nodeId to get documents from), topN (int, default 3).
// Calls POST {embeddingURL}/rerank with {"query": q, "documents": [...], "topN": n}.
// Falls back to returning the first topN documents as-is when the rerank endpoint is unavailable.
type rerankExecutor struct {
	embeddingURL string
}

func (e *rerankExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	query, _ := node.Config["query"].(string)
	documentsFrom, _ := node.Config["documentsFrom"].(string)
	topN := 3
	if v, ok := node.Config["topN"].(float64); ok && v > 0 {
		topN = int(v)
	}

	// Resolve query from upstream node output when not set directly.
	if query == "" && documentsFrom != "" {
		if out, ok := pctx.GetNodeOutput(documentsFrom); ok {
			if q, ok := out["query"].(string); ok {
				query = q
			}
		}
	}
	if query == "" {
		return nil, fmt.Errorf("rerank node: missing required config 'query'")
	}

	// Collect documents from the upstream node.
	var documents []any
	if documentsFrom != "" {
		if out, ok := pctx.GetNodeOutput(documentsFrom); ok {
			if docs, ok := out["results"].([]any); ok {
				documents = docs
			} else if docs, ok := out["documents"].([]any); ok {
				documents = docs
			}
		}
	}

	// Helper: fall back to first topN documents without reranking.
	fallback := func() map[string]any {
		if len(documents) <= topN {
			return map[string]any{"results": documents}
		}
		return map[string]any{"results": documents[:topN]}
	}

	if e.embeddingURL == "" || len(documents) == 0 {
		slog.Warn("rerank node: skipping rerank (no embedding URL or empty documents)", "nodeId", node.ID)
		return fallback(), nil
	}

	payload, _ := json.Marshal(map[string]any{
		"query":     query,
		"documents": documents,
		"topN":      topN,
	})
	req, err := newHTTPRequest(ctx, http.MethodPost, e.embeddingURL+"/rerank", payload)
	if err != nil {
		slog.Warn("rerank node: failed to build request, falling back", "nodeId", node.ID, "err", err)
		return fallback(), nil
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		slog.Warn("rerank node: rerank service unavailable, falling back", "nodeId", node.ID, "err", err)
		return fallback(), nil
	}
	defer resp.Body.Close() //nolint:errcheck

	if resp.StatusCode != http.StatusOK {
		slog.Warn("rerank node: rerank service returned non-200, falling back",
			"nodeId", node.ID, "status", resp.StatusCode)
		return fallback(), nil
	}

	var result struct {
		Results []any `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		slog.Warn("rerank node: decode error, falling back", "nodeId", node.ID, "err", err)
		return fallback(), nil
	}

	return map[string]any{"results": result.Results}, nil
}

// connectSQL opens a pgxpool connection to dsn.
// The pool is single-use and the caller is responsible for closing it.
func connectSQL(ctx context.Context, dsn string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse DSN: %w", err)
	}
	cfg.MaxConns = 2
	return pgxpool.NewWithConfig(ctx, cfg)
}

package execution

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/expr-lang/expr"

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
func NewNodeRegistry(providerRegistry *ai.ProviderRegistry, skillRuntimeURL string, embeddingURL string) *NodeRegistry {
	r := &NodeRegistry{executors: make(map[string]NodeExecutor)}
	// Basic
	r.executors["INPUT"] = &inputExecutor{}
	r.executors["OUTPUT"] = &outputExecutor{}
	r.executors["TRANSFORM"] = &transformExecutor{}
	// AI
	r.executors["LLM"] = &llmExecutor{registry: providerRegistry}
	r.executors["EMBED"] = &embedExecutor{embeddingURL: embeddingURL}
	// Data
	r.executors["SQL"] = &sqlExecutor{}
	r.executors["HTTP"] = &httpExecutor{}
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

type sqlExecutor struct{}

func (e *sqlExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	query, _ := node.Config["query"].(string)
	if query == "" {
		return nil, fmt.Errorf("sql node: missing required config 'query'")
	}
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	var params map[string]any
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			params = out
		}
	}
	slog.Debug("sql node queued", "nodeId", node.ID)
	return map[string]any{"query": query, "params": params, "status": "queued"}, nil
}

// --- HTTP ---

type httpExecutor struct{}

func (e *httpExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	method, _ := node.Config["method"].(string)
	if method == "" {
		method = "GET"
	}
	url, _ := node.Config["url"].(string)
	if url == "" {
		return nil, fmt.Errorf("http node: missing required config 'url'")
	}
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	var input map[string]any
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			input = out
		}
	}
	slog.Debug("http node queued", "nodeId", node.ID, "method", method, "url", url)
	return map[string]any{"method": method, "url": url, "input": input, "status": "queued"}, nil
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

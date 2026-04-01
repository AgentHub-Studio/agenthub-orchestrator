package execution

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"time"

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
func NewNodeRegistry(providerRegistry *ai.ProviderRegistry, skillRuntimeURL string) *NodeRegistry {
	r := &NodeRegistry{executors: make(map[string]NodeExecutor)}
	// Basic
	r.executors["INPUT"] = &inputExecutor{}
	r.executors["OUTPUT"] = &outputExecutor{}
	r.executors["TRANSFORM"] = &transformExecutor{}
	// AI
	r.executors["LLM"] = &llmExecutor{registry: providerRegistry}
	r.executors["EMBED"] = &embedExecutor{}
	// Data
	r.executors["SQL"] = &sqlExecutor{skillRuntimeURL: skillRuntimeURL}
	r.executors["HTTP"] = &httpExecutor{}
	r.executors["DOCUMENT_SEARCH"] = &documentSearchExecutor{skillRuntimeURL: skillRuntimeURL}
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

type embedExecutor struct{}

func (e *embedExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
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

	return map[string]any{
		"text":      text,
		"embedding": nil, // populated by embedding service at runtime
	}, nil
}

// --- SQL ---

// sqlExecutor delegates SQL execution to the skill-runtime service.
// Node config: skillSlug (required), datasourceId (required), query (required), inputNodeId (optional).
type sqlExecutor struct {
	skillRuntimeURL string
}

func (e *sqlExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	skillSlug, _ := node.Config["skillSlug"].(string)
	if skillSlug == "" {
		return nil, fmt.Errorf("sql node: missing required config 'skillSlug'")
	}
	query, _ := node.Config["query"].(string)
	if query == "" {
		return nil, fmt.Errorf("sql node: missing required config 'query'")
	}
	datasourceID, _ := node.Config["datasourceId"].(string)

	input := map[string]any{
		"query":        query,
		"datasourceId": datasourceID,
	}
	// Merge upstream node output as additional params.
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			for k, v := range out {
				if _, exists := input[k]; !exists {
					input[k] = v
				}
			}
		}
	}

	if e.skillRuntimeURL == "" {
		return nil, fmt.Errorf("sql node: skill-runtime URL not configured")
	}
	return callSkillRuntime(ctx, e.skillRuntimeURL, skillSlug, input)
}

// --- HTTP ---

type httpExecutor struct{}

func (e *httpExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	method, _ := node.Config["method"].(string)
	if method == "" {
		method = "GET"
	}
	rawURL, _ := node.Config["url"].(string)
	if rawURL == "" {
		return nil, fmt.Errorf("http node: missing required config 'url'")
	}

	// Build request body from upstream node output or explicit body config.
	var bodyBytes []byte
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			bodyBytes, _ = json.Marshal(out)
		}
	} else if body, ok := node.Config["body"]; ok {
		bodyBytes, _ = json.Marshal(body)
	}

	req, err := newHTTPRequest(ctx, method, rawURL, bodyBytes)
	if err != nil {
		return nil, fmt.Errorf("http node: build request: %w", err)
	}

	// Apply custom headers from config.
	if headers, ok := node.Config["headers"].(map[string]any); ok {
		for k, v := range headers {
			if vs, ok := v.(string); ok {
				req.Header.Set(k, vs)
			}
		}
	}
	if len(bodyBytes) > 0 && req.Header.Get("Content-Type") == "" {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("http node: request failed: %w", err)
	}
	defer resp.Body.Close()

	var responseBody any
	if err := json.NewDecoder(resp.Body).Decode(&responseBody); err != nil {
		responseBody = nil
	}

	slog.Debug("http node executed", "nodeId", node.ID, "method", method, "url", rawURL, "status", resp.StatusCode)
	return map[string]any{
		"statusCode": resp.StatusCode,
		"body":       responseBody,
		"ok":         resp.StatusCode >= 200 && resp.StatusCode < 300,
	}, nil
}

// --- DOCUMENT_SEARCH ---

// documentSearchExecutor delegates vector search to the skill-runtime service.
// Node config: skillSlug (required), knowledgeBaseId (required), query (optional), inputNodeId (optional), topK (optional).
type documentSearchExecutor struct {
	skillRuntimeURL string
}

func (e *documentSearchExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	skillSlug, _ := node.Config["skillSlug"].(string)
	if skillSlug == "" {
		return nil, fmt.Errorf("document_search node: missing required config 'skillSlug'")
	}
	knowledgeBaseID, _ := node.Config["knowledgeBaseId"].(string)
	if knowledgeBaseID == "" {
		return nil, fmt.Errorf("document_search node: missing required config 'knowledgeBaseId'")
	}

	// Resolve query from upstream output or explicit config.
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

	topK, _ := node.Config["topK"].(float64)
	if topK == 0 {
		topK = 5
	}

	input := map[string]any{
		"knowledgeBaseId": knowledgeBaseID,
		"query":           query,
		"topK":            int(topK),
	}

	if e.skillRuntimeURL == "" {
		return nil, fmt.Errorf("document_search node: skill-runtime URL not configured")
	}
	return callSkillRuntime(ctx, e.skillRuntimeURL, skillSlug, input)
}

// callSkillRuntime is shared helper used by SQL and DOCUMENT_SEARCH executors.
func callSkillRuntime(ctx context.Context, skillRuntimeURL, skillSlug string, input map[string]any) (map[string]any, error) {
	body, _ := json.Marshal(input)
	req, err := newHTTPRequest(ctx, "POST", skillRuntimeURL+"/api/skills/"+skillSlug+"/execute", body)
	if err != nil {
		return nil, fmt.Errorf("skill-runtime request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := defaultHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("skill-runtime request failed: %w", err)
	}
	defer resp.Body.Close()
	var result map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("skill-runtime decode response: %w", err)
	}
	return result, nil
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

func (e *ifExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	condition, _ := node.Config["condition"].(string)
	inputNodeID, _ := node.Config["inputNodeId"].(string)

	// Evaluate simple key=value conditions against upstream node output.
	branch := "true"
	if inputNodeID != "" && condition != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			branch = evaluateCondition(condition, out)
		}
	}
	return map[string]any{"condition": condition, "branch": branch}, nil
}

// evaluateCondition evaluates a simple "key==value" or "key!=value" expression.
func evaluateCondition(condition string, data map[string]any) string {
	for op, negate := range map[string]bool{"==": false, "!=": true} {
		parts := splitTwo(condition, op)
		if len(parts) != 2 {
			continue
		}
		key, expected := parts[0], parts[1]
		actual := fmt.Sprintf("%v", data[key])
		match := actual == expected
		if negate {
			match = !match
		}
		if match {
			return "true"
		}
		return "false"
	}
	return "true" // default when condition cannot be evaluated
}

func splitTwo(s, sep string) []string {
	idx := -1
	for i := 0; i <= len(s)-len(sep); i++ {
		if s[i:i+len(sep)] == sep {
			idx = i
			break
		}
	}
	if idx < 0 {
		return nil
	}
	return []string{s[:idx], s[idx+len(sep):]}
}

// --- FOREACH ---

type foreachExecutor struct{}

func (e *foreachExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	itemsKey, _ := node.Config["itemsKey"].(string)
	if itemsKey == "" {
		itemsKey = "items"
	}
	var items []any
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if v, ok := out[itemsKey].([]any); ok {
				items = v
			}
		}
	}
	return map[string]any{"items": items, "count": len(items), "status": "foreach_ready"}, nil
}

// --- SWITCH ---

type switchExecutor struct{}

func (e *switchExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	inputNodeID, _ := node.Config["inputNodeId"].(string)
	valueKey, _ := node.Config["valueKey"].(string)
	cases, _ := node.Config["cases"].(map[string]any)

	var value string
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if v, ok := out[valueKey].(string); ok {
				value = v
			}
		}
	}
	branch := "default"
	if cases != nil {
		if b, ok := cases[value].(string); ok {
			branch = b
		}
	}
	return map[string]any{"value": value, "branch": branch}, nil
}

// --- MERGE ---

type mergeExecutor struct{}

func (e *mergeExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
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

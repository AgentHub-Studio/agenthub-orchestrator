package execution

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
)

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
func NewNodeRegistry(providerRegistry *ai.ProviderRegistry) *NodeRegistry {
	r := &NodeRegistry{executors: make(map[string]NodeExecutor)}
	r.executors["INPUT"] = &inputExecutor{}
	r.executors["OUTPUT"] = &outputExecutor{}
	r.executors["TRANSFORM"] = &transformExecutor{}
	r.executors["IF"] = &ifExecutor{}
	r.executors["LLM"] = &llmExecutor{registry: providerRegistry}
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

// --- INPUT node ---

type inputExecutor struct{}

func (e *inputExecutor) Execute(_ context.Context, _ *Node, pctx *PipelineContext) (map[string]any, error) {
	// INPUT nodes pass through the pipeline input.
	return pctx.Input, nil
}

// --- OUTPUT node ---

type outputExecutor struct{}

func (e *outputExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	// OUTPUT nodes collect outputs from specified upstream nodes.
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

// --- TRANSFORM node ---

type transformExecutor struct{}

func (e *transformExecutor) Execute(_ context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	// TRANSFORM nodes merge outputs from upstream nodes and apply key mappings.
	mappings, _ := node.Config["mappings"].(map[string]any) // {"outputKey": "nodeId.key"}
	result := map[string]any{}
	if mappings == nil {
		// No mappings: just merge all upstream outputs.
		for _, out := range pctx.Snapshot() {
			for k, v := range out {
				result[k] = v
			}
		}
		return result, nil
	}

	for outKey, ref := range mappings {
		refStr, _ := ref.(string)
		result[outKey] = refStr // placeholder; full expression evaluation would use a template engine
	}
	return result, nil
}

// --- IF node ---

type ifExecutor struct{}

func (e *ifExecutor) Execute(_ context.Context, node *Node, _ *PipelineContext) (map[string]any, error) {
	// IF nodes evaluate a condition and return branch routing info.
	condition, _ := node.Config["condition"].(string)
	// Simple evaluation: check if a context value equals expected.
	// Full expression evaluation would use goja or similar.
	result := map[string]any{
		"condition": condition,
		"branch":    "true", // default; real impl evaluates condition
	}
	return result, nil
}

// --- LLM node ---

type llmExecutor struct {
	registry *ai.ProviderRegistry
}

func (e *llmExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	// LLM nodes call a language model. Config specifies model, system prompt, and input source.
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

	// Resolve provider from registry.
	if e.registry == nil {
		return nil, fmt.Errorf("llm node: no AI provider registry configured")
	}

	var model ai.ChatModel
	var err error
	if providerName != "" {
		model, err = e.registry.GetDefault(providerName)
	} else {
		// Fall back to any available provider.
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

	opts := ai.ChatOptions{Model: modelName}
	resp, err := model.Chat(ctx, messages, opts)
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

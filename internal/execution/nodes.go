package execution

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
)

// NodeExecutor processes a single pipeline node.
type NodeExecutor interface {
	Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error)
}

// nodeExecutorRegistry maps node types to their executors.
var nodeExecutorRegistry = map[string]NodeExecutor{
	"INPUT":     &inputExecutor{},
	"OUTPUT":    &outputExecutor{},
	"TRANSFORM": &transformExecutor{},
	"IF":        &ifExecutor{},
	"LLM":       &llmExecutor{},
}

// GetNodeExecutor returns the executor for nodeType or an error if not found.
func GetNodeExecutor(nodeType string) (NodeExecutor, error) {
	e, ok := nodeExecutorRegistry[nodeType]
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

type llmExecutor struct{}

func (e *llmExecutor) Execute(ctx context.Context, node *Node, pctx *PipelineContext) (map[string]any, error) {
	// LLM nodes call a language model. Config specifies model, system prompt, and input source.
	systemPrompt, _ := node.Config["systemPrompt"].(string)
	inputNodeID, _ := node.Config["inputNodeId"].(string)

	var userMessage string
	if inputNodeID != "" {
		if out, ok := pctx.GetNodeOutput(inputNodeID); ok {
			if msg, ok := out["message"].(string); ok {
				userMessage = msg
			} else {
				// Serialize the whole output as JSON.
				b, _ := json.Marshal(out)
				userMessage = string(b)
			}
		}
	}

	if userMessage == "" {
		userMessage, _ = node.Config["prompt"].(string)
	}

	slog.Debug("llm node executing", "nodeId", node.ID, "systemPrompt", systemPrompt, "userMessage", userMessage)

	// NOTE: In a full implementation, this would call the ai.ChatModel via the registry.
	// For now, return a placeholder indicating the model was invoked.
	return map[string]any{
		"response":     fmt.Sprintf("[LLM response for: %s]", userMessage),
		"model":        node.Config["model"],
		"finish_reason": "stop",
	}, nil
}

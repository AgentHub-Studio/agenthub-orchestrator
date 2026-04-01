// Package aisetup wires concrete LLM provider implementations into the ai.ProviderRegistry.
// It lives outside the ai package to avoid import cycles: providers import ai, and this
// package imports both providers and ai.
package aisetup

import (
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/anthropic"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/ollama"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/openai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/openrouter"
)

// NewRegistryFromEnv builds a ProviderRegistry populated from environment variables.
// Providers without an API key (when required) are silently omitted — call
// Registry.Available() to confirm what was registered.
//
// Registered provider type keys (for GetDefault): "OPENAI", "ANTHROPIC", "OLLAMA", "OPENROUTER"
func NewRegistryFromEnv(cfg ai.EnvConfig) *ai.ProviderRegistry {
	r := ai.NewProviderRegistry()

	if cfg.OpenAIAPIKey != "" {
		r.Register("OPENAI", openai.New(cfg.OpenAIAPIKey, cfg.OpenAIBaseURL))
	}
	if cfg.AnthropicAPIKey != "" {
		r.Register("ANTHROPIC", anthropic.New(cfg.AnthropicAPIKey, cfg.AnthropicBaseURL))
	}
	// Ollama does not require an API key.
	if cfg.OllamaBaseURL != "" {
		r.Register("OLLAMA", ollama.New(cfg.OllamaBaseURL))
	}
	if cfg.OpenRouterAPIKey != "" {
		r.Register("OPENROUTER", openrouter.New(cfg.OpenRouterAPIKey, cfg.OpenRouterBaseURL, "agenthub"))
	}

	return r
}

package aisetup_test

import (
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/aisetup"
)

func TestNewRegistryFromEnv_OllamaNoKeyRequired(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "")
	t.Setenv("ANTHROPIC_API_KEY", "")
	t.Setenv("OPENROUTER_API_KEY", "")
	t.Setenv("OLLAMA_BASE_URL", "http://ollama.local:11434")

	cfg := ai.EnvConfigFromEnvironment()
	r := aisetup.NewRegistryFromEnv(cfg)

	available := r.Available()
	assert.Contains(t, available, "OLLAMA")
	assert.NotContains(t, available, "OPENAI")
	assert.NotContains(t, available, "ANTHROPIC")
}

func TestNewRegistryFromEnv_APIKeyRequiredProviders(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "sk-test-key")
	t.Setenv("ANTHROPIC_API_KEY", "")
	t.Setenv("OPENROUTER_API_KEY", "or-test-key")
	// OLLAMA_BASE_URL defaults to http://localhost:11434 when empty, so Ollama
	// is always registered. This is intentional — Ollama doesn't need an API key.

	cfg := ai.EnvConfigFromEnvironment()
	r := aisetup.NewRegistryFromEnv(cfg)

	available := r.Available()
	assert.Contains(t, available, "OPENAI")
	assert.NotContains(t, available, "ANTHROPIC")
	assert.Contains(t, available, "OPENROUTER")
	assert.Contains(t, available, "OLLAMA") // always registered (default URL used)
}

func TestNewRegistryFromEnv_EmptyEnv(t *testing.T) {
	t.Setenv("OPENAI_API_KEY", "")
	t.Setenv("ANTHROPIC_API_KEY", "")
	t.Setenv("OPENROUTER_API_KEY", "")
	t.Setenv("OLLAMA_BASE_URL", "")

	cfg := ai.EnvConfigFromEnvironment()
	r := aisetup.NewRegistryFromEnv(cfg)
	// Ollama default URL is set, so it will still be registered.
	// Just ensure no panic and registry is usable.
	assert.NotNil(t, r)
}

package ai_test

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
)

// stubModel is a minimal ChatModel for testing the registry.
type stubModel struct{ providerName string }

func (s *stubModel) GetProviderName() string { return s.providerName }
func (s *stubModel) Chat(_ context.Context, _ []ai.Message, _ ai.ChatOptions) (*ai.ChatResponse, error) {
	return &ai.ChatResponse{Content: "stub"}, nil
}
func (s *stubModel) ChatStream(_ context.Context, _ []ai.Message, _ ai.ChatOptions) (<-chan ai.StreamChunk, error) {
	ch := make(chan ai.StreamChunk)
	close(ch)
	return ch, nil
}

func TestProviderRegistry_RegisterAndGet(t *testing.T) {
	r := ai.NewProviderRegistry()
	model := &stubModel{providerName: "openai"}
	r.Register("gpt-4o", model)

	got, err := r.Get("gpt-4o")
	require.NoError(t, err)
	assert.Equal(t, model, got)
}

func TestProviderRegistry_Get_NotFound(t *testing.T) {
	r := ai.NewProviderRegistry()
	_, err := r.Get("nonexistent")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not registered")
}

func TestProviderRegistry_GetDefault_FirstRegistered(t *testing.T) {
	r := ai.NewProviderRegistry()
	first := &stubModel{providerName: "openai"}
	second := &stubModel{providerName: "openai"}

	r.Register("gpt-4o", first)
	r.Register("gpt-4o-mini", second)

	def, err := r.GetDefault("openai")
	require.NoError(t, err)
	assert.Equal(t, first, def, "first registered should be the default")
}

func TestProviderRegistry_GetDefault_NotFound(t *testing.T) {
	r := ai.NewProviderRegistry()
	_, err := r.GetDefault("anthropic")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no default provider")
}

func TestProviderRegistry_Available_Sorted(t *testing.T) {
	r := ai.NewProviderRegistry()
	r.Register("openai-gpt4", &stubModel{providerName: "openai"})
	r.Register("anthropic-haiku", &stubModel{providerName: "anthropic"})
	r.Register("ollama-llama", &stubModel{providerName: "ollama"})

	names := r.Available()
	assert.Equal(t, []string{"anthropic-haiku", "ollama-llama", "openai-gpt4"}, names)
}

func TestProviderRegistry_Available_Empty(t *testing.T) {
	r := ai.NewProviderRegistry()
	assert.Empty(t, r.Available())
}

func TestProviderRegistry_MultipleProviderTypes(t *testing.T) {
	r := ai.NewProviderRegistry()
	openaiModel := &stubModel{providerName: "openai"}
	anthropicModel := &stubModel{providerName: "anthropic"}

	r.Register("gpt-4o", openaiModel)
	r.Register("claude-3", anthropicModel)

	def, err := r.GetDefault("openai")
	require.NoError(t, err)
	assert.Equal(t, openaiModel, def)

	def, err = r.GetDefault("anthropic")
	require.NoError(t, err)
	assert.Equal(t, anthropicModel, def)
}

package ai_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
)

// stubModel is a minimal ChatModel for testing.
type stubModel struct{ name string }

func (s *stubModel) Chat(_ context.Context, _ []ai.Message, _ ai.ChatOptions) (*ai.ChatResponse, error) {
	return &ai.ChatResponse{Content: "ok"}, nil
}
func (s *stubModel) ChatStream(_ context.Context, _ []ai.Message, _ ai.ChatOptions) (<-chan ai.StreamChunk, error) {
	ch := make(chan ai.StreamChunk)
	close(ch)
	return ch, nil
}
func (s *stubModel) GetProviderName() string { return s.name }

func TestLlmPresetConfig_ToChatOptions(t *testing.T) {
	cfg := ai.LlmPresetConfig{
		Name:    "gpt-4-preset",
		ModelID: "gpt-4o",
		Provider: "OPENAI",
		ConfigJSON: json.RawMessage(`{"temperature":0.7,"max_tokens":1024,"system_message":"You are a helpful assistant"}`),
	}
	opts, err := cfg.ToChatOptions()
	require.NoError(t, err)
	assert.Equal(t, "gpt-4o", opts.Model)
	assert.InDelta(t, 0.7, opts.Temperature, 1e-9)
	assert.Equal(t, 1024, opts.MaxTokens)
	assert.Equal(t, "You are a helpful assistant", opts.SystemMsg)
}

func TestLlmPresetConfig_ToChatOptions_Empty(t *testing.T) {
	cfg := ai.LlmPresetConfig{ModelID: "gpt-3.5-turbo"}
	opts, err := cfg.ToChatOptions()
	require.NoError(t, err)
	assert.Equal(t, "gpt-3.5-turbo", opts.Model)
	assert.Zero(t, opts.Temperature)
}

func TestProviderRegistry_GetChatModel_ByPresetName(t *testing.T) {
	r := ai.NewProviderRegistry()
	model := &stubModel{name: "openai"}
	r.Register("my-gpt4-preset", model)

	preset := ai.LlmPresetConfig{Name: "my-gpt4-preset", Provider: "OPENAI", ModelID: "gpt-4"}
	got, opts, err := r.GetChatModel(preset)
	require.NoError(t, err)
	assert.Same(t, model, got)
	assert.Equal(t, "gpt-4", opts.Model)
}

func TestProviderRegistry_GetChatModel_ByProviderType(t *testing.T) {
	r := ai.NewProviderRegistry()
	model := &stubModel{name: "OPENAI"}
	r.Register("OPENAI", model)

	preset := ai.LlmPresetConfig{Name: "unknown-preset", Provider: "OPENAI", ModelID: "gpt-4o-mini"}
	got, opts, err := r.GetChatModel(preset)
	require.NoError(t, err)
	assert.Same(t, model, got)
	assert.Equal(t, "gpt-4o-mini", opts.Model)
}

func TestProviderRegistry_GetChatModel_NotFound(t *testing.T) {
	r := ai.NewProviderRegistry()
	preset := ai.LlmPresetConfig{Name: "ghost", Provider: "GROQ", ModelID: "llama3"}
	_, _, err := r.GetChatModel(preset)
	require.Error(t, err)
	assert.True(t, errors.Is(err, ai.ErrProviderNotFound))
}

func TestProviderRegistry_ThreadSafe(t *testing.T) {
	r := ai.NewProviderRegistry()
	done := make(chan struct{})
	for i := 0; i < 50; i++ {
		go func(i int) {
			r.Register("OPENAI", &stubModel{name: "openai"})
			r.Available()
			done <- struct{}{}
		}(i)
	}
	for i := 0; i < 50; i++ {
		<-done
	}
}

func TestEnvConfig_Defaults(t *testing.T) {
	// Ensure no panic and sensible defaults when env vars are unset.
	t.Setenv("OPENAI_API_KEY", "")
	t.Setenv("ANTHROPIC_API_KEY", "")
	cfg := ai.EnvConfigFromEnvironment()
	assert.Equal(t, "https://api.openai.com/v1", cfg.OpenAIBaseURL)
	assert.Equal(t, "https://api.anthropic.com", cfg.AnthropicBaseURL)
	assert.Equal(t, "http://localhost:11434", cfg.OllamaBaseURL)
	assert.Equal(t, "https://openrouter.ai/api/v1", cfg.OpenRouterBaseURL)
}


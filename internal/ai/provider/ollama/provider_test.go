package ollama_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/ollama"
)

func TestOllamaProvider_GetProviderName(t *testing.T) {
	p := ollama.New("")
	assert.Equal(t, "ollama", p.GetProviderName())
}

func TestOllamaProvider_DefaultBaseURL(t *testing.T) {
	// New with empty URL should not panic; provider is created successfully.
	p := ollama.New("")
	assert.NotNil(t, p)
}

func TestOllamaProvider_Chat_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/chat/completions", r.URL.Path)
		// Ollama uses an empty API key — Authorization header has no key portion.
		assert.Equal(t, "Bearer", r.Header.Get("Authorization"))

		resp := map[string]any{
			"id":    "chat-1",
			"model": "llama3",
			"choices": []map[string]any{
				{
					"message": map[string]any{
						"role":    "assistant",
						"content": "Olá do Ollama!",
					},
					"finish_reason": "stop",
				},
			},
			"usage": map[string]any{
				"prompt_tokens": 5, "completion_tokens": 8, "total_tokens": 13,
			},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := ollama.New(srv.URL)
	msgs := []ai.Message{{Role: ai.RoleUser, Content: "Olá"}}
	res, err := p.Chat(context.Background(), msgs, ai.ChatOptions{Model: "llama3"})
	require.NoError(t, err)
	assert.Equal(t, "Olá do Ollama!", res.Content)
	assert.Equal(t, "stop", res.FinishReason)
	assert.Equal(t, 13, res.Usage.TotalTokens)
}

func TestOllamaProvider_Chat_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	p := ollama.New(srv.URL)
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "llama3"})
	require.Error(t, err)
}

func TestOllamaProvider_ChatStream_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		chunks := []string{
			`{"choices":[{"delta":{"role":"assistant","content":"Hello"},"finish_reason":""}]}`,
			`{"choices":[{"delta":{"role":"assistant","content":" world"},"finish_reason":"stop"}]}`,
		}
		for _, c := range chunks {
			_, _ = w.Write([]byte("data: " + c + "\n\n"))
		}
		_, _ = w.Write([]byte("data: [DONE]\n\n"))
	}))
	defer srv.Close()

	p := ollama.New(srv.URL)
	ch, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "llama3"})
	require.NoError(t, err)

	var chunks []ai.StreamChunk
	for c := range ch {
		chunks = append(chunks, c)
	}
	assert.Len(t, chunks, 2)
	assert.Equal(t, "Hello", chunks[0].Delta)
	assert.Equal(t, " world", chunks[1].Delta)
	assert.Equal(t, "stop", chunks[1].FinishReason)
}

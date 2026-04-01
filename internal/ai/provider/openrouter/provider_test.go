package openrouter_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/openrouter"
)

func TestOpenRouterProvider_GetProviderName(t *testing.T) {
	p := openrouter.New("key", "", "")
	assert.Equal(t, "openrouter", p.GetProviderName())
}

func TestOpenRouterProvider_Chat_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/chat/completions", r.URL.Path)
		assert.Equal(t, "Bearer test-key", r.Header.Get("Authorization"))
		assert.Equal(t, "https://agenthub.dev", r.Header.Get("HTTP-Referer"))

		resp := map[string]any{
			"model": "anthropic/claude-3-haiku",
			"choices": []map[string]any{
				{
					"message": map[string]any{
						"role":    "assistant",
						"content": "Response from OpenRouter",
					},
					"finish_reason": "stop",
				},
			},
			"usage": map[string]any{
				"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30,
			},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := openrouter.New("test-key", srv.URL, "")
	msgs := []ai.Message{{Role: ai.RoleUser, Content: "Hello"}}
	res, err := p.Chat(context.Background(), msgs, ai.ChatOptions{Model: "anthropic/claude-3-haiku"})
	require.NoError(t, err)
	assert.Equal(t, "Response from OpenRouter", res.Content)
	assert.Equal(t, "stop", res.FinishReason)
	assert.Equal(t, 30, res.Usage.TotalTokens)
	assert.Equal(t, "anthropic/claude-3-haiku", res.Model)
}

func TestOpenRouterProvider_Chat_WithAppName(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "AgentHub", r.Header.Get("X-Title"))

		resp := map[string]any{
			"model": "openai/gpt-4o",
			"choices": []map[string]any{
				{"message": map[string]any{"role": "assistant", "content": "ok"}, "finish_reason": "stop"},
			},
			"usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := openrouter.New("key", srv.URL, "AgentHub")
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "openai/gpt-4o"})
	require.NoError(t, err)
}

func TestOpenRouterProvider_Chat_NoAppName_OmitsXTitle(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Empty(t, r.Header.Get("X-Title"))

		resp := map[string]any{
			"model": "openai/gpt-4o",
			"choices": []map[string]any{
				{"message": map[string]any{"role": "assistant", "content": "ok"}, "finish_reason": "stop"},
			},
			"usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := openrouter.New("key", srv.URL, "")
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "openai/gpt-4o"})
	require.NoError(t, err)
}

func TestOpenRouterProvider_Chat_APIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	p := openrouter.New("bad-key", srv.URL, "")
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "openai/gpt-4o"})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "401")
}

func TestOpenRouterProvider_Chat_WithSystemMsg(t *testing.T) {
	var receivedMessages []ai.Message
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Messages []ai.Message `json:"messages"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		receivedMessages = body.Messages

		resp := map[string]any{
			"model": "openai/gpt-4o",
			"choices": []map[string]any{
				{"message": map[string]any{"role": "assistant", "content": "ok"}, "finish_reason": "stop"},
			},
			"usage": map[string]any{"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := openrouter.New("key", srv.URL, "")
	msgs := []ai.Message{{Role: ai.RoleUser, Content: "Tell me a joke"}}
	_, err := p.Chat(context.Background(), msgs, ai.ChatOptions{
		Model:     "openai/gpt-4o",
		SystemMsg: "You are a comedian.",
	})
	require.NoError(t, err)
	require.Len(t, receivedMessages, 2)
	assert.Equal(t, ai.RoleSystem, receivedMessages[0].Role)
	assert.Equal(t, "You are a comedian.", receivedMessages[0].Content)
}

func TestOpenRouterProvider_ChatStream_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		chunks := []string{
			`{"choices":[{"delta":{"role":"assistant","content":"Hello"},"finish_reason":""}]}`,
			`{"choices":[{"delta":{"role":"assistant","content":" there"},"finish_reason":"stop"}]}`,
		}
		for _, c := range chunks {
			_, _ = w.Write([]byte("data: " + c + "\n\n"))
		}
		_, _ = w.Write([]byte("data: [DONE]\n\n"))
	}))
	defer srv.Close()

	p := openrouter.New("key", srv.URL, "")
	ch, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "openai/gpt-4o"})
	require.NoError(t, err)

	var chunks []ai.StreamChunk
	for c := range ch {
		chunks = append(chunks, c)
	}
	assert.Len(t, chunks, 2)
	assert.Equal(t, "Hello", chunks[0].Delta)
	assert.Equal(t, " there", chunks[1].Delta)
	assert.Equal(t, "stop", chunks[1].FinishReason)
}

func TestOpenRouterProvider_ChatStream_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()

	p := openrouter.New("key", srv.URL, "")
	_, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "hi"}}, ai.ChatOptions{Model: "openai/gpt-4o"})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "429")
}

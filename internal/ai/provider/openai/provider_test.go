package openai_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/openai"
)

func TestOpenAIProvider_GetProviderName(t *testing.T) {
	p := openai.New("key", "")
	assert.Equal(t, "openai", p.GetProviderName())
}

func TestOpenAIProvider_Chat_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/chat/completions", r.URL.Path)
		assert.Equal(t, "Bearer test-key", r.Header.Get("Authorization"))

		resp := map[string]any{
			"id":    "chatcmpl-1",
			"model": "gpt-4o",
			"choices": []map[string]any{
				{
					"index": 0,
					"message": map[string]any{
						"role":    "assistant",
						"content": "Hello, world!",
					},
					"finish_reason": "stop",
				},
			},
			"usage": map[string]any{
				"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
			},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := openai.New("test-key", srv.URL)
	msgs := []ai.Message{{Role: ai.RoleUser, Content: "Hi"}}
	res, err := p.Chat(context.Background(), msgs, ai.ChatOptions{Model: "gpt-4o"})
	require.NoError(t, err)
	assert.Equal(t, "Hello, world!", res.Content)
	assert.Equal(t, "stop", res.FinishReason)
	assert.Equal(t, 15, res.Usage.TotalTokens)
}

func TestOpenAIProvider_Chat_APIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"message":"invalid api key","type":"invalid_request_error"}}`))
	}))
	defer srv.Close()

	p := openai.New("bad-key", srv.URL)
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}}, ai.ChatOptions{Model: "gpt-4o"})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "openai")
}

func TestOpenAIProvider_Chat_WithSystemMessage(t *testing.T) {
	var received map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&received)
		resp := map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"role": "assistant", "content": "done"}, "finish_reason": "stop"},
			},
			"usage": map[string]any{"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7},
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := openai.New("key", srv.URL)
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}},
		ai.ChatOptions{Model: "gpt-4o", SystemMsg: "You are helpful."})
	require.NoError(t, err)

	// System message should be first in the messages array.
	msgs := received["messages"].([]any)
	first := msgs[0].(map[string]any)
	assert.Equal(t, "system", first["role"])
}

func TestOpenAIProvider_ChatStream_Tokens(t *testing.T) {
	sse := "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n" +
		"data: {\"choices\":[{\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n" +
		"data: [DONE]\n\n"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(sse))
	}))
	defer srv.Close()

	p := openai.New("key", srv.URL)
	ch, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}},
		ai.ChatOptions{Model: "gpt-4o"})
	require.NoError(t, err)

	var got string
	for chunk := range ch {
		require.NoError(t, chunk.Error)
		got += chunk.Delta
	}
	assert.Equal(t, "Hello world", got)
}

func TestOpenAIProvider_ChatStream_APIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":{"message":"server error"}}`))
	}))
	defer srv.Close()

	p := openai.New("key", srv.URL)
	_, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}},
		ai.ChatOptions{Model: "gpt-4o"})
	require.Error(t, err)
}

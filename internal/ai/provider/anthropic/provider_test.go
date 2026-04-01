package anthropic_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/anthropic"
)

func TestAnthropicProvider_GetProviderName(t *testing.T) {
	p := anthropic.New("key", "")
	assert.Equal(t, "anthropic", p.GetProviderName())
}

func TestAnthropicProvider_Chat_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/messages", r.URL.Path)
		assert.Equal(t, "test-key", r.Header.Get("x-api-key"))

		resp := map[string]any{
			"id":         "msg_01",
			"model":      "claude-3-5-sonnet-20241022",
			"stop_reason": "end_turn",
			"content": []map[string]any{
				{"type": "text", "text": "Hello from Anthropic!"},
			},
			"usage": map[string]any{"input_tokens": 10, "output_tokens": 6},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer srv.Close()

	p := anthropic.New("test-key", srv.URL)
	msgs := []ai.Message{{Role: ai.RoleUser, Content: "Hi"}}
	res, err := p.Chat(context.Background(), msgs, ai.ChatOptions{Model: "claude-3-5-sonnet-20241022", MaxTokens: 256})
	require.NoError(t, err)
	assert.Equal(t, "Hello from Anthropic!", res.Content)
	assert.Equal(t, "stop", res.FinishReason) // Anthropic "end_turn" is mapped to "stop"
	assert.Equal(t, 16, res.Usage.TotalTokens)
}

func TestAnthropicProvider_Chat_APIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"type":"error","error":{"type":"authentication_error","message":"invalid api key"}}`))
	}))
	defer srv.Close()

	p := anthropic.New("bad-key", srv.URL)
	_, err := p.Chat(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}},
		ai.ChatOptions{Model: "claude-3-5-sonnet-20241022", MaxTokens: 256})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "anthropic")
}

func TestAnthropicProvider_ChatStream_Tokens(t *testing.T) {
	sse := "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n" +
		"event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" there\"}}\n\n" +
		"event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte(sse))
	}))
	defer srv.Close()

	p := anthropic.New("key", srv.URL)
	ch, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}},
		ai.ChatOptions{Model: "claude-3-5-sonnet-20241022", MaxTokens: 256})
	require.NoError(t, err)

	var got string
	for chunk := range ch {
		require.NoError(t, chunk.Error)
		got += chunk.Delta
	}
	assert.Equal(t, "Hi there", got)
}

func TestAnthropicProvider_ChatStream_APIError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"type":"error","error":{"message":"server error"}}`))
	}))
	defer srv.Close()

	p := anthropic.New("key", srv.URL)
	_, err := p.ChatStream(context.Background(), []ai.Message{{Role: ai.RoleUser, Content: "Hi"}},
		ai.ChatOptions{Model: "claude-3-5-sonnet-20241022", MaxTokens: 256})
	require.Error(t, err)
}

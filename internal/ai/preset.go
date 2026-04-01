package ai

import (
	"encoding/json"
	"errors"
	"fmt"
)

// ErrProviderNotFound is returned when the requested provider type is not registered.
var ErrProviderNotFound = errors.New("ai: provider not found")

// ErrProviderNotConfigured is returned when a provider is registered but has no API key.
var ErrProviderNotConfigured = errors.New("ai: provider not configured (missing API key)")

// LlmPresetConfig mirrors the `llm_config_preset` table and carries all
// configuration needed to obtain a ChatModel from the ProviderRegistry.
type LlmPresetConfig struct {
	ID           string          `json:"id"`
	Name         string          `json:"name"`
	Provider     string          `json:"provider"` // "OPENAI", "ANTHROPIC", "OLLAMA", "OPENROUTER"
	ModelID      string          `json:"modelId"`
	ConfigJSON   json.RawMessage `json:"configJson"` // {"temperature":0.7,"max_tokens":1024,...}
	IsDefault    bool            `json:"isDefault"`
	TenantID     string          `json:"tenantId,omitempty"` // empty = global preset
}

// presetConfigJSON contains optional fields stored in config_json.
type presetConfigJSON struct {
	Temperature      float64 `json:"temperature"`
	MaxTokens        int     `json:"max_tokens"`
	TopP             float64 `json:"top_p"`
	SystemMessage    string  `json:"system_message"`
}

// ToChatOptions converts the preset configuration to ChatOptions.
// Fields set to zero value in ConfigJSON are left as zero (caller's Merge applies defaults).
func (p LlmPresetConfig) ToChatOptions() (ChatOptions, error) {
	opts := ChatOptions{
		Model: p.ModelID,
	}
	if len(p.ConfigJSON) > 0 {
		var cfg presetConfigJSON
		if err := json.Unmarshal(p.ConfigJSON, &cfg); err != nil {
			return ChatOptions{}, fmt.Errorf("ai preset: parse config_json: %w", err)
		}
		opts.Temperature = cfg.Temperature
		opts.MaxTokens = cfg.MaxTokens
		opts.TopP = cfg.TopP
		opts.SystemMsg = cfg.SystemMessage
	}
	return opts, nil
}

package config

import (
	"fmt"
	"os"
	"strings"
)

// LLMProviderConfig holds configuration for a single LLM provider.
type LLMProviderConfig struct {
	APIKey  string
	BaseURL string // optional override
}

// Config holds all configuration for agenthub-orchestrator.
type Config struct {
	Port            string
	DatabaseURL     string
	KeycloakBaseURL string
	SkillRuntimeURL string
	EmbeddingURL    string
	RabbitMQURL     string
	OTLPEndpoint    string
	CORSOrigins     []string
	LogLevel        string

	// LLM provider configurations.
	OpenAI      LLMProviderConfig
	Anthropic   LLMProviderConfig
	Ollama      LLMProviderConfig
	OpenRouter  LLMProviderConfig
}

// Load reads configuration from environment variables.
func Load() (*Config, error) {
	cfg := &Config{
		Port:            getEnv("PORT", "8084"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		KeycloakBaseURL: os.Getenv("KEYCLOAK_BASE_URL"),
		SkillRuntimeURL: getEnv("SKILL_RUNTIME_URL", "http://agenthub-skill-runtime:8085"),
		EmbeddingURL:    getEnv("EMBEDDING_URL", "http://agenthub-embedding:8000"),
		RabbitMQURL:     os.Getenv("RABBITMQ_URL"),
		OTLPEndpoint:    os.Getenv("OTLP_ENDPOINT"),
		LogLevel:        getEnv("LOG_LEVEL", "info"),
	}

	if cfg.DatabaseURL == "" {
		return nil, fmt.Errorf("config: DATABASE_URL is required")
	}
	if cfg.KeycloakBaseURL == "" {
		return nil, fmt.Errorf("config: KEYCLOAK_BASE_URL is required")
	}

	corsOrigins := getEnv("CORS_ORIGINS", "*")
	cfg.CORSOrigins = strings.Split(corsOrigins, ",")

	cfg.OpenAI = LLMProviderConfig{
		APIKey:  os.Getenv("OPENAI_API_KEY"),
		BaseURL: os.Getenv("OPENAI_BASE_URL"),
	}
	cfg.Anthropic = LLMProviderConfig{
		APIKey:  os.Getenv("ANTHROPIC_API_KEY"),
		BaseURL: os.Getenv("ANTHROPIC_BASE_URL"),
	}
	cfg.Ollama = LLMProviderConfig{
		BaseURL: getEnv("OLLAMA_BASE_URL", "http://ollama:11434"),
	}
	cfg.OpenRouter = LLMProviderConfig{
		APIKey:  os.Getenv("OPENROUTER_API_KEY"),
		BaseURL: os.Getenv("OPENROUTER_BASE_URL"),
	}

	return cfg, nil
}

func getEnv(key, defaultValue string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultValue
}

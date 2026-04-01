package config

import (
	"fmt"
	"os"
	"strings"
)

// Config holds all configuration for agenthub-orchestrator.
type Config struct {
	Port            string
	DatabaseURL     string
	KeycloakBaseURL string
	SkillRuntimeURL string
	RabbitMQURL     string
	OTLPEndpoint    string
	CORSOrigins     []string
	LogLevel        string
}

// Load reads configuration from environment variables.
func Load() (*Config, error) {
	cfg := &Config{
		Port:            getEnv("PORT", "8084"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		KeycloakBaseURL: os.Getenv("KEYCLOAK_BASE_URL"),
		SkillRuntimeURL: getEnv("SKILL_RUNTIME_URL", "http://agenthub-skill-runtime:8085"),
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

	return cfg, nil
}

func getEnv(key, defaultValue string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultValue
}

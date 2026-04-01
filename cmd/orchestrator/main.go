package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/anthropic"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/ollama"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/openai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai/provider/openrouter"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/config"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/database"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/server"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		slog.Error("failed to load config", "err", err)
		os.Exit(1)
	}

	setupLogger(cfg.LogLevel)

	ctx := context.Background()

	pool, err := database.NewPool(ctx, cfg.DatabaseURL)
	if err != nil {
		slog.Error("failed to connect to database", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	providerRegistry := buildProviderRegistry(cfg)

	srv := server.New(cfg, pool, providerRegistry)

	httpServer := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      srv,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		slog.Info("server starting", "addr", httpServer.Addr)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	<-quit
	slog.Info("shutting down server...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}

	slog.Info("server stopped")
}

// buildProviderRegistry creates an AI provider registry from config.
// Providers with no API key are skipped silently.
func buildProviderRegistry(cfg *config.Config) *ai.ProviderRegistry {
	reg := ai.NewProviderRegistry()

	if cfg.OpenAI.APIKey != "" {
		p := openai.New(cfg.OpenAI.APIKey, cfg.OpenAI.BaseURL)
		reg.Register("openai", p)
		slog.Info("ai: registered openai provider")
	}
	if cfg.Anthropic.APIKey != "" {
		p := anthropic.New(cfg.Anthropic.APIKey, cfg.Anthropic.BaseURL)
		reg.Register("anthropic", p)
		slog.Info("ai: registered anthropic provider")
	}
	if cfg.Ollama.BaseURL != "" {
		p := ollama.New(cfg.Ollama.BaseURL)
		reg.Register("ollama", p)
		slog.Info("ai: registered ollama provider", "baseURL", cfg.Ollama.BaseURL)
	}
	if cfg.OpenRouter.APIKey != "" {
		p := openrouter.New(cfg.OpenRouter.APIKey, cfg.OpenRouter.BaseURL, "agenthub-orchestrator")
		reg.Register("openrouter", p)
		slog.Info("ai: registered openrouter provider")
	}

	return reg
}

func setupLogger(level string) {
	var lvl slog.Level
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: lvl})))
}

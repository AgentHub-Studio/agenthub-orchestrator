package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/config"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/execution"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/middleware"
)

// New creates the HTTP server with all routes mounted.
func New(cfg *config.Config, pool *pgxpool.Pool) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.Logger)
	r.Use(middleware.Recovery)
	r.Use(middleware.CORS(cfg.CORSOrigins))

	execHandler := execution.NewHandler(pool, cfg.SkillRuntimeURL)
	r.Mount("/api/executions", execHandler.Routes())

	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})

	return r
}

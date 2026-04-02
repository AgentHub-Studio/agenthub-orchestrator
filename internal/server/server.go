package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	gocommons_auth "github.com/AgentHub-Studio/agenthub-go-commons/auth"
	gocommons_tenant "github.com/AgentHub-Studio/agenthub-go-commons/tenant"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/ai"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/config"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/execution"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/middleware"
)

// New creates the HTTP server with all routes mounted.
func New(cfg *config.Config, pool *pgxpool.Pool, providerRegistry *ai.ProviderRegistry, publisher execution.EventPublisher) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.Logger)
	r.Use(middleware.Recovery)
	r.Use(middleware.CORS(cfg.CORSOrigins))

	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})

	nodeRegistry := execution.NewNodeRegistry(providerRegistry, cfg.SkillRuntimeURL, cfg.EmbeddingURL, cfg.AgentHubAPIURL)
	apiClient := execution.NewAPIClient(cfg.AgentHubAPIURL)
	execHandler := execution.NewHandler(pool, nodeRegistry, publisher, apiClient)

	// Protected API routes — require valid Keycloak JWT
	r.Group(func(r chi.Router) {
		r.Use(gocommons_auth.Middleware(gocommons_auth.Config{KeycloakBaseURL: cfg.KeycloakBaseURL}))
		r.Use(gocommons_tenant.Middleware())
		r.Mount("/api/executions", execHandler.Routes())
	})

	return r
}

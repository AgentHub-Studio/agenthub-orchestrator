package server_test

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/config"
	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/server"
)

func testServer(t *testing.T) http.Handler {
	t.Helper()
	cfg := &config.Config{
		KeycloakBaseURL: "http://keycloak.example.com",
		CORSOrigins:     []string{"*"},
		SkillRuntimeURL: "http://skill-runtime:8085",
		EmbeddingURL:    "http://embedding:8000",
	}
	return server.New(cfg, nil, nil, nil)
}

func TestServer_Health_ReturnsOK(t *testing.T) {
	h := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}

func TestServer_ProtectedRoute_RejectsNoToken(t *testing.T) {
	h := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/api/executions", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestServer_ProtectedRoute_RejectsInvalidToken(t *testing.T) {
	h := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/api/executions", nil)
	req.Header.Set("Authorization", "Bearer not-a-valid-jwt")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestServer_CORS_HeaderPresent(t *testing.T) {
	h := testServer(t)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Origin", "http://localhost:4200")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	require.Equal(t, http.StatusOK, w.Code)
	assert.NotEmpty(t, w.Header().Get("Access-Control-Allow-Origin"))
}

func TestServer_New_HandlerNotNil(t *testing.T) {
	h := testServer(t)
	assert.NotNil(t, h)
}

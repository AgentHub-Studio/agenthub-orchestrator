package execution_test

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/execution"
)

func newHandler() http.Handler {
	h := execution.NewHandler(nil, nil, nil)
	return h.Routes()
}

func TestHandler_DeprecationHeaders(t *testing.T) {
	// Use GET endpoints that will fail gracefully (bad UUID → 400)
	// rather than POST which panics on nil pool.
	tests := []struct {
		name   string
		method string
		path   string
	}{
		{"GET /{id}", http.MethodGet, "/not-a-uuid"},
		{"GET /agent/{agentId}", http.MethodGet, "/agent/not-a-uuid"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(tc.method, tc.path, nil)
			w := httptest.NewRecorder()
			newHandler().ServeHTTP(w, req)

			assert.Equal(t, "true", w.Header().Get("Deprecated"),
				"should have Deprecated: true header")
			assert.Equal(t, "2026-07-01", w.Header().Get("Sunset"),
				"should have Sunset header")
			// These return 400 due to invalid UUID, but headers are still set.
			assert.Equal(t, http.StatusBadRequest, w.Code)
		})
	}
}

func TestHandler_DeprecationHeaders_Cancel(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/not-a-uuid/cancel", nil)
	w := httptest.NewRecorder()
	newHandler().ServeHTTP(w, req)

	assert.Equal(t, "true", w.Header().Get("Deprecated"))
	assert.Equal(t, "2026-07-01", w.Header().Get("Sunset"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

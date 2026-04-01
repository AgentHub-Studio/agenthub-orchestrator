package execution

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Handler handles HTTP requests for agent executions.
type Handler struct {
	svc *Service
}

// NewHandler creates an execution Handler with the given node registry and optional event publisher.
func NewHandler(pool *pgxpool.Pool, nodeRegistry *NodeRegistry, publisher EventPublisher) *Handler {
	return &Handler{svc: NewService(pool, nodeRegistry, publisher)}
}

// Routes returns the chi router for execution endpoints.
func (h *Handler) Routes() http.Handler {
	r := chi.NewRouter()
	r.Post("/", h.start)
	r.Get("/{id}", h.get)
	r.Post("/{id}/cancel", h.cancel)
	r.Get("/agent/{agentId}", h.listByAgent)
	return r
}

func tenantFromRequest(r *http.Request) string {
	// In production, extract from JWT claim. Use header for now.
	t := r.Header.Get("X-Tenant-ID")
	if t == "" {
		return "default"
	}
	return t
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v) //nolint:errcheck
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func (h *Handler) start(w http.ResponseWriter, r *http.Request) {
	var req CreateExecutionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	tenantID := tenantFromRequest(r)
	exec, err := h.svc.StartExecution(r.Context(), tenantID, req)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, ResponseFrom(exec))
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid execution id")
		return
	}
	exec, err := h.svc.GetByID(r.Context(), tenantFromRequest(r), id)
	if err != nil {
		writeError(w, http.StatusNotFound, "execution not found")
		return
	}
	writeJSON(w, http.StatusOK, ResponseFrom(exec))
}

func (h *Handler) cancel(w http.ResponseWriter, r *http.Request) {
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid execution id")
		return
	}
	if err := h.svc.CancelExecution(r.Context(), tenantFromRequest(r), id); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) listByAgent(w http.ResponseWriter, r *http.Request) {
	agentID, err := uuid.Parse(chi.URLParam(r, "agentId"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid agent id")
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	size, _ := strconv.Atoi(r.URL.Query().Get("size"))
	if size <= 0 {
		size = 20
	}

	execs, total, err := h.svc.ListByAgent(r.Context(), tenantFromRequest(r), agentID, page, size)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	responses := make([]AgentExecutionResponse, len(execs))
	for i, e := range execs {
		responses[i] = ResponseFrom(e)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"content":       responses,
		"totalElements": total,
		"page":          page,
		"size":          size,
	})
}

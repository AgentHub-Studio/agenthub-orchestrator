-- AgentHub Orchestrator — Tenant Schema (ah_{tenantId})
-- Execution tracking tables. No tenant_id column — isolation is enforced by schema.

-- =============================================================================
-- AGENT EXECUTION
-- Top-level record of a single pipeline run triggered for a tenant's agent.
-- =============================================================================

CREATE TABLE IF NOT EXISTS agent_execution (
    id                  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    agent_id            UUID        NOT NULL,
    agent_version_id    UUID        NOT NULL,
    trigger_type        VARCHAR(50) NOT NULL,   -- MANUAL, SCHEDULED, API, WEBHOOK
    triggered_by        UUID,
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    input_json          JSONB,
    context_json        JSONB,
    result_json         JSONB,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_execution_agent   ON agent_execution (agent_id);
CREATE INDEX IF NOT EXISTS idx_execution_status  ON agent_execution (status);
CREATE INDEX IF NOT EXISTS idx_execution_started ON agent_execution (started_at);

-- =============================================================================
-- AGENT EXECUTION NODE
-- Per-node execution record within a pipeline run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS agent_execution_node (
    id                  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    execution_id        UUID        NOT NULL REFERENCES agent_execution (id) ON DELETE CASCADE,
    pipeline_node_id    UUID,
    parent_node_id      UUID,
    node_type           VARCHAR(50)  NOT NULL,   -- INPUT, OUTPUT, TRANSFORM, LLM, HTTP, TOOL, etc.
    node_name           VARCHAR(200) NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'PENDING', -- PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    input_json          JSONB,
    output_json         JSONB,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    latency_ms          BIGINT,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exec_node_execution ON agent_execution_node (execution_id);
CREATE INDEX IF NOT EXISTS idx_exec_node_pipeline  ON agent_execution_node (pipeline_node_id);
CREATE INDEX IF NOT EXISTS idx_exec_node_status    ON agent_execution_node (status);
CREATE INDEX IF NOT EXISTS idx_exec_node_started   ON agent_execution_node (started_at);

-- =============================================================================
-- EXECUTION CONTEXT
-- Snapshot of the shared pipeline context JSON at any point during a run.
-- =============================================================================

CREATE TABLE IF NOT EXISTS execution_context (
    id              UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    execution_id    UUID        NOT NULL REFERENCES agent_execution (id) ON DELETE CASCADE,
    context_json    JSONB       NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_exec_ctx_execution ON execution_context (execution_id);
CREATE INDEX IF NOT EXISTS idx_exec_ctx_updated   ON execution_context (updated_at);

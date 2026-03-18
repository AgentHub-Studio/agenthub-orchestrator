-- AgentHub Orchestrator — Public Schema baseline
-- Execution tables live in per-tenant schemas (ah_{tenantId}), not here.
-- This file establishes the Flyway baseline for the public schema.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

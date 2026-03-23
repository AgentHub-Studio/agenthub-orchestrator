package com.agenthub.orchestrator.shared.multitenant;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate {@link CurrentTenantIdentifierResolver} that resolves the current tenant's
 * PostgreSQL schema name from {@link TenantContextHolder}.
 *
 * <p>Returns {@code ah_<tenantId>} when a tenant context is set; falls back to
 * {@code public} (schema-less) when no context is present (e.g. during startup or
 * Flyway migrations).
 *
 * @since 1.0.0
 */
@Slf4j
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    private static final String DEFAULT_SCHEMA = "public";

    @Override
    public String resolveCurrentTenantIdentifier() {
        TenantContext ctx = TenantContextHolder.getContext();
        if (ctx == null || ctx.getTenantId() == null) {
            return DEFAULT_SCHEMA;
        }
        return "ah_" + ctx.getTenantId();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}

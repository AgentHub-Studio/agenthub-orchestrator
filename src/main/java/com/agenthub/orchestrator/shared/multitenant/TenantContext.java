package com.agenthub.orchestrator.shared.multitenant;

import lombok.Getter;

@Getter
public class TenantContext {

    private final String tenantId;
    private final String userId;

    public TenantContext(String tenantId) {
        this.tenantId = tenantId;
        this.userId = null;
    }

    public TenantContext(String tenantId, String userId) {
        this.tenantId = tenantId;
        this.userId = userId;
    }
}

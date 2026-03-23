package com.agenthub.orchestrator.adapter.in.rest;

import com.agenthub.orchestrator.domain.execution.model.ExecutionState;
import com.agenthub.orchestrator.adapter.out.persistence.ExecutionEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mapper for execution persistence view.
 *
 * @since 1.0.0
 */
@Component
public class ExecutionMapper {

    public Map<String, Object> toResultJson(ExecutionState state) {
        return Map.of(
            "nodeResults", state.getNodeResults(),
            "completedNodes", state.getCompletedNodes(),
            "failedNodes", state.getFailedNodes(),
            "skippedNodes", state.getSkippedNodes()
        );
    }

    public String toStatus(ExecutionState state) {
        return state.getStatus().name();
    }

    public boolean isTerminal(ExecutionEntity entity) {
        return entity != null && (
            "COMPLETED".equals(entity.getStatus())
                || "FAILED".equals(entity.getStatus())
                || "CANCELLED".equals(entity.getStatus())
                || "TIMED_OUT".equals(entity.getStatus())
        );
    }
}

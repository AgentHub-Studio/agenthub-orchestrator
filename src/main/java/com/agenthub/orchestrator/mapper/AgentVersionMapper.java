package com.agenthub.orchestrator.mapper;

import com.agenthub.orchestrator.entity.AgentVersionEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Mapper for AgentVersion entity.
 *
 * @since 1.0.0
 */
@Component
public class AgentVersionMapper {

    public Map<String, Object> toPipelineJson(AgentVersionEntity entity) {
        return entity != null ? entity.getPipelineDefinitionJson() : null;
    }

    public void applyPipelineJson(AgentVersionEntity entity, Map<String, Object> pipelineJson) {
        if (entity != null) {
            entity.setPipelineDefinitionJson(pipelineJson);
        }
    }
}

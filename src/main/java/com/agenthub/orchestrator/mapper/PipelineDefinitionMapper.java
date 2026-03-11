package com.agenthub.orchestrator.mapper;

import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineEdge;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mapper for PipelineDefinition
 * 
 * Converts between JSON (stored in database) and domain objects.
 * 
 * @since 1.0.0
 */
@Component
public class PipelineDefinitionMapper {
    
    private final ObjectMapper objectMapper;
    
    public PipelineDefinitionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Convert JSON map to PipelineDefinition domain object
     * 
     * Expected JSON structure:
     * {
     *   "id": "uuid",
     *   "agentId": "uuid",
     *   "name": "Pipeline Name",
     *   "version": 1,
     *   "entryNodeId": "input",
     *   "nodes": [...],
     *   "edges": [...]
     * }
     */
    public PipelineDefinition fromJson(Map<String, Object> json) {
        if (json == null) {
            return null;
        }
        
        try {
            UUID id = UUID.fromString((String) json.get("id"));
            UUID agentId = UUID.fromString((String) json.get("agentId"));
            String name = (String) json.get("name");
            Integer version = (Integer) json.get("version");
            String entryNodeId = (String) json.get("entryNodeId");
            
            // Parse nodes
            List<Map<String, Object>> nodesJson = (List<Map<String, Object>>) json.get("nodes");
            List<PipelineNode> nodes = nodesJson.stream()
                .map(this::nodeFromJson)
                .toList();
            
            // Parse edges
            List<Map<String, Object>> edgesJson = (List<Map<String, Object>>) json.get("edges");
            List<PipelineEdge> edges = edgesJson.stream()
                .map(this::edgeFromJson)
                .toList();
            
            return new PipelineDefinition(id, agentId, name, version, entryNodeId, nodes, edges);
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse pipeline definition from JSON", e);
        }
    }
    
    /**
     * Convert PipelineDefinition to JSON map (for storage)
     */
    public Map<String, Object> toJson(PipelineDefinition pipeline) {
        if (pipeline == null) {
            return null;
        }
        
        return Map.of(
            "id", pipeline.id().toString(),
            "agentId", pipeline.agentId().toString(),
            "name", pipeline.name(),
            "version", pipeline.version(),
            "entryNodeId", pipeline.entryNodeId(),
            "nodes", pipeline.nodes().stream().map(this::nodeToJson).toList(),
            "edges", pipeline.edges().stream().map(this::edgeToJson).toList()
        );
    }
    
    private PipelineNode nodeFromJson(Map<String, Object> json) {
        String id = (String) json.get("id");
        String typeStr = (String) json.get("type");
        String name = (String) json.get("name");
        Map<String, Object> config = (Map<String, Object>) json.getOrDefault("config", Map.of());
        
        // Parse position if present
        Map<String, Object> positionJson = (Map<String, Object>) json.get("position");
        PipelineNode.Position position = null;
        if (positionJson != null) {
            Integer x = (Integer) positionJson.get("x");
            Integer y = (Integer) positionJson.get("y");
            position = new PipelineNode.Position(x, y);
        }
        
        return new PipelineNode(
            id,
            com.agenthub.orchestrator.domain.node.NodeType.valueOf(typeStr),
            name,
            config,
            position
        );
    }
    
    private Map<String, Object> nodeToJson(PipelineNode node) {
        Map<String, Object> json = new java.util.HashMap<>();
        json.put("id", node.id());
        json.put("type", node.type().name());
        json.put("name", node.name());
        json.put("config", node.config());
        
        if (node.position() != null) {
            json.put("position", Map.of(
                "x", node.position().x(),
                "y", node.position().y()
            ));
        }
        
        return json;
    }
    
    private PipelineEdge edgeFromJson(Map<String, Object> json) {
        String id = (String) json.get("id");
        String sourceNodeId = (String) json.get("sourceNodeId");
        String targetNodeId = (String) json.get("targetNodeId");
        String sourcePort = (String) json.getOrDefault("sourcePort", "output");
        String targetPort = (String) json.getOrDefault("targetPort", "input");
        String conditionLabel = (String) json.get("conditionLabel");
        
        return new PipelineEdge(id, sourceNodeId, targetNodeId, sourcePort, targetPort, conditionLabel);
    }
    
    private Map<String, Object> edgeToJson(PipelineEdge edge) {
        Map<String, Object> json = new java.util.HashMap<>();
        json.put("id", edge.id());
        json.put("sourceNodeId", edge.sourceNodeId());
        json.put("targetNodeId", edge.targetNodeId());
        json.put("sourcePort", edge.sourcePort());
        json.put("targetPort", edge.targetPort());
        
        if (edge.conditionLabel() != null) {
            json.put("conditionLabel", edge.conditionLabel());
        }
        
        return json;
    }
}

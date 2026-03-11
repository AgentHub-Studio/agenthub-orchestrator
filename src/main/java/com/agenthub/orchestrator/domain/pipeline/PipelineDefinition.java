package com.agenthub.orchestrator.domain.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Complete definition of a pipeline DAG
 * 
 * Immutable aggregate root.
 * Represents the structure of a pipeline (nodes and edges).
 * Loaded from agent_version.pipeline_definition_json.
 * 
 * Based on specification: PIPELINE_DAG.md
 * 
 * @param id Pipeline definition ID (usually agent_version_id)
 * @param agentId Agent this pipeline belongs to
 * @param name Pipeline name
 * @param version Version number
 * @param entryNodeId ID of entry node (usually "input")
 * @param nodes List of nodes in the pipeline
 * @param edges List of edges connecting nodes
 * 
 * @since 1.0.0
 */
public record PipelineDefinition(
    @JsonProperty("id") UUID id,
    @JsonProperty("agentId") UUID agentId,
    @JsonProperty("name") String name,
    @JsonProperty("version") Integer version,
    @JsonProperty("entryNodeId") String entryNodeId,
    @JsonProperty("nodes") List<PipelineNode> nodes,
    @JsonProperty("edges") List<PipelineEdge> edges
) {
    
    /**
     * Canonical constructor with validation
     */
    public PipelineDefinition(
        UUID id,
        UUID agentId,
        String name,
        Integer version,
        String entryNodeId,
        List<PipelineNode> nodes,
        List<PipelineEdge> edges
    ) {
        if (id == null) {
            throw new IllegalArgumentException("Pipeline id cannot be null");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("Agent id cannot be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pipeline name cannot be null or blank");
        }
        if (version == null || version < 1) {
            throw new IllegalArgumentException("Pipeline version must be >= 1");
        }
        if (entryNodeId == null || entryNodeId.isBlank()) {
            throw new IllegalArgumentException("Entry node id cannot be null or blank");
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("Pipeline must have at least one node");
        }
        if (edges == null) {
            throw new IllegalArgumentException("Edges cannot be null (use empty list)");
        }
        
        // Store validated values with immutable copies
        this.id = id;
        this.agentId = agentId;
        this.name = name;
        this.version = version;
        this.entryNodeId = entryNodeId;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        
        // Validate structure
        validateStructure();
    }
    
    /**
     * Validate pipeline structure
     */
    private void validateStructure() {
        // Check entry node exists
        if (getNodeById(entryNodeId).isEmpty()) {
            throw new IllegalArgumentException("Entry node '" + entryNodeId + "' not found in nodes");
        }
        
        // Check all edge references exist
        Set<String> nodeIds = nodes.stream()
            .map(PipelineNode::id)
            .collect(Collectors.toSet());
        
        for (PipelineEdge edge : edges) {
            if (!nodeIds.contains(edge.sourceNodeId())) {
                throw new IllegalArgumentException(
                    "Edge '" + edge.id() + "' references non-existent source node '" + edge.sourceNodeId() + "'"
                );
            }
            if (!nodeIds.contains(edge.targetNodeId())) {
                throw new IllegalArgumentException(
                    "Edge '" + edge.id() + "' references non-existent target node '" + edge.targetNodeId() + "'"
                );
            }
        }
        
        // Check for cycles (simplified check - full DAG validation in service)
        if (hasSelfLoops()) {
            throw new IllegalArgumentException("Pipeline cannot have self-loops (node connected to itself)");
        }
    }
    
    /**
     * Get node by ID
     */
    public Optional<PipelineNode> getNodeById(String nodeId) {
        return nodes.stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst();
    }
    
    /**
     * Get all outgoing edges from a node
     */
    public List<PipelineEdge> getOutgoingEdges(String nodeId) {
        return edges.stream()
            .filter(edge -> edge.sourceNodeId().equals(nodeId))
            .toList();
    }
    
    /**
     * Get all incoming edges to a node
     */
    public List<PipelineEdge> getIncomingEdges(String nodeId) {
        return edges.stream()
            .filter(edge -> edge.targetNodeId().equals(nodeId))
            .toList();
    }
    
    /**
     * Get successor nodes (nodes connected via outgoing edges)
     */
    public List<String> getSuccessors(String nodeId) {
        return getOutgoingEdges(nodeId).stream()
            .map(PipelineEdge::targetNodeId)
            .distinct()
            .toList();
    }
    
    /**
     * Get predecessor nodes (nodes connected via incoming edges)
     */
    public List<String> getPredecessors(String nodeId) {
        return getIncomingEdges(nodeId).stream()
            .map(PipelineEdge::sourceNodeId)
            .distinct()
            .toList();
    }
    
    /**
     * Check if pipeline has cycles (simple check for self-loops)
     */
    private boolean hasSelfLoops() {
        return edges.stream()
            .anyMatch(edge -> edge.sourceNodeId().equals(edge.targetNodeId()));
    }
    
    /**
     * Get entry node
     */
    public PipelineNode getEntryNode() {
        return getNodeById(entryNodeId)
            .orElseThrow(() -> new IllegalStateException("Entry node not found"));
    }
    
    /**
     * Get all node IDs
     */
    public Set<String> getAllNodeIds() {
        return nodes.stream()
            .map(PipelineNode::id)
            .collect(Collectors.toSet());
    }
    
    /**
     * Get node count
     */
    public int getNodeCount() {
        return nodes.size();
    }
    
    /**
     * Get edge count
     */
    public int getEdgeCount() {
        return edges.size();
    }
    
    /**
     * Check if node exists
     */
    public boolean hasNode(String nodeId) {
        return getNodeById(nodeId).isPresent();
    }
}

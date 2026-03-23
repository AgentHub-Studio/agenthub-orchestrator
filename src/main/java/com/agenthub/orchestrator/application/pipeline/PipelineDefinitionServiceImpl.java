package com.agenthub.orchestrator.application.pipeline;

import com.agenthub.orchestrator.domain.pipeline.PipelineDefinition;
import com.agenthub.orchestrator.domain.pipeline.PipelineEdge;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.adapter.out.persistence.AgentVersionEntity;
import com.agenthub.orchestrator.domain.exception.InvalidPipelineException;
import com.agenthub.orchestrator.domain.exception.PipelineNotFoundException;
import com.agenthub.orchestrator.adapter.in.rest.PipelineDefinitionMapper;
import com.agenthub.orchestrator.domain.port.AgentVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Implementation of PipelineDefinitionService
 * 
 * Uses Spring Cache for performance.
 * Validates DAG structure using topological sort and cycle detection.
 * 
 * @since 1.0.0
 */
@Service
public class PipelineDefinitionServiceImpl implements PipelineDefinitionService {
    
    private static final Logger log = LoggerFactory.getLogger(PipelineDefinitionServiceImpl.class);
    
    private final AgentVersionRepository agentVersionRepository;
    private final PipelineDefinitionMapper pipelineDefinitionMapper;
    
    public PipelineDefinitionServiceImpl(
        AgentVersionRepository agentVersionRepository,
        PipelineDefinitionMapper pipelineDefinitionMapper
    ) {
        this.agentVersionRepository = agentVersionRepository;
        this.pipelineDefinitionMapper = pipelineDefinitionMapper;
    }
    
    @Override
    @Cacheable(value = "pipelineDefinitions", key = "#pipelineId")
    public PipelineDefinition loadPipelineDefinition(UUID pipelineId, UUID tenantId) {
        log.debug("Loading pipeline definition: pipelineId={}, tenantId={}", pipelineId, tenantId);

        AgentVersionEntity agentVersion = agentVersionRepository
            .findById(pipelineId)
            .orElseThrow(() -> new PipelineNotFoundException(pipelineId));

        PipelineDefinition pipeline = pipelineDefinitionMapper.fromJson(agentVersion.getPipelineDefinitionJson());
        ValidationResult validation = validatePipeline(pipeline);
        if (!validation.isValid()) {
            throw new InvalidPipelineException(validation.getErrors());
        }

        return pipeline;
    }
    
    @Override
    @Cacheable(value = "activePipelines", key = "#agentId")
    public PipelineDefinition getActivePipeline(UUID agentId, UUID tenantId) {
        log.debug("Loading active pipeline for agent: agentId={}, tenantId={}", agentId, tenantId);

        AgentVersionEntity activeVersion = agentVersionRepository
            .findLatestPublishedByAgentId(agentId)
            .orElseThrow(() -> new PipelineNotFoundException(agentId, "No active published version found"));

        return loadPipelineDefinition(activeVersion.getId(), tenantId);
    }
    
    @Override
    public ValidationResult validatePipeline(PipelineDefinition pipeline) {
        log.debug("Validating pipeline: id={}, name={}", pipeline.id(), pipeline.name());
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // 1. Check entry node exists
        if (!pipeline.hasNode(pipeline.entryNodeId())) {
            errors.add("Entry node '" + pipeline.entryNodeId() + "' does not exist");
            return ValidationResult.failure(errors);
        }
        
        // 2. Check for cycles using DFS
        if (hasCycles(pipeline)) {
            errors.add("Pipeline contains cycles (not a valid DAG)");
        }
        
        // 3. Check all nodes are reachable from entry node
        Set<String> reachableNodes = getReachableNodes(pipeline);
        Set<String> allNodes = pipeline.getAllNodeIds();
        
        Set<String> unreachableNodes = new HashSet<>(allNodes);
        unreachableNodes.removeAll(reachableNodes);
        
        if (!unreachableNodes.isEmpty()) {
            warnings.add("Unreachable nodes (orphans): " + unreachableNodes);
        }
        
        // 4. Check for nodes with no outgoing edges (dead ends)
        List<String> deadEndNodes = findDeadEndNodes(pipeline);
        if (!deadEndNodes.isEmpty()) {
            warnings.add("Dead-end nodes (no outgoing edges): " + deadEndNodes);
        }
        
        // 5. Check for duplicate node IDs (should be caught by domain model)
        long uniqueNodeIds = allNodes.size();
        long totalNodes = pipeline.nodes().size();
        if (uniqueNodeIds != totalNodes) {
            errors.add("Duplicate node IDs detected");
        }
        
        // 6. Check for duplicate edge IDs
        Set<String> edgeIds = new HashSet<>();
        for (PipelineEdge edge : pipeline.edges()) {
            if (!edgeIds.add(edge.id())) {
                errors.add("Duplicate edge ID: " + edge.id());
            }
        }
        
        // Return result
        if (errors.isEmpty()) {
            if (warnings.isEmpty()) {
                return ValidationResult.success();
            } else {
                return ValidationResult.successWithWarnings(warnings);
            }
        } else {
            return ValidationResult.failure(errors, warnings);
        }
    }
    
    @Override
    @CacheEvict(value = {"pipelineDefinitions", "activePipelines"}, key = "#pipelineId")
    public void invalidateCache(UUID pipelineId) {
        log.debug("Invalidating cache for pipeline: {}", pipelineId);
    }
    
    @Override
    @CacheEvict(value = {"pipelineDefinitions", "activePipelines"}, allEntries = true)
    public void clearCache() {
        log.debug("Clearing all pipeline caches");
    }
    
    // ===== Private Helper Methods =====
    
    /**
     * Check for cycles using DFS with cycle detection
     */
    private boolean hasCycles(PipelineDefinition pipeline) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String nodeId : pipeline.getAllNodeIds()) {
            if (hasCycleDFS(nodeId, pipeline, visited, recursionStack)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean hasCycleDFS(
        String nodeId,
        PipelineDefinition pipeline,
        Set<String> visited,
        Set<String> recursionStack
    ) {
        if (recursionStack.contains(nodeId)) {
            return true; // Cycle detected
        }
        
        if (visited.contains(nodeId)) {
            return false; // Already processed
        }
        
        visited.add(nodeId);
        recursionStack.add(nodeId);
        
        // Visit all successors
        for (String successor : pipeline.getSuccessors(nodeId)) {
            if (hasCycleDFS(successor, pipeline, visited, recursionStack)) {
                return true;
            }
        }
        
        recursionStack.remove(nodeId);
        return false;
    }
    
    /**
     * Get all nodes reachable from entry node using BFS
     */
    private Set<String> getReachableNodes(PipelineDefinition pipeline) {
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        
        queue.add(pipeline.entryNodeId());
        reachable.add(pipeline.entryNodeId());
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            
            for (String successor : pipeline.getSuccessors(current)) {
                if (reachable.add(successor)) {
                    queue.add(successor);
                }
            }
        }
        
        return reachable;
    }
    
    /**
     * Find nodes with no outgoing edges (except OUTPUT nodes)
     */
    private List<String> findDeadEndNodes(PipelineDefinition pipeline) {
        List<String> deadEnds = new ArrayList<>();
        
        for (PipelineNode node : pipeline.nodes()) {
            if (pipeline.getOutgoingEdges(node.id()).isEmpty()) {
                // OUTPUT nodes are expected to have no outgoing edges
                if (node.type() != com.agenthub.orchestrator.domain.node.model.NodeType.OUTPUT) {
                    deadEnds.add(node.id());
                }
            }
        }
        
        return deadEnds;
    }
}

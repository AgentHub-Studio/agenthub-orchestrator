package com.agenthub.orchestrator.application.executor;

import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.exception.UnsupportedNodeTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for node executors
 * 
 * Maintains a map of NodeType -> NodeExecutor and provides lookup.
 * Automatically discovers all NodeExecutor beans at startup.
 * 
 * @since 1.0.0
 */
@Component
public class NodeExecutorRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(NodeExecutorRegistry.class);
    
    private final Map<NodeType, NodeExecutor> executors = new ConcurrentHashMap<>();
    
    /**
     * Constructor - auto-discovers all NodeExecutor beans
     */
    public NodeExecutorRegistry(List<NodeExecutor> executorList) {
        for (NodeExecutor executor : executorList) {
            NodeType type = executor.getSupportedType();
            executors.put(type, executor);
            logger.info("Registered executor for node type: {}", type);
        }
        logger.info("Total executors registered: {}", executors.size());
    }
    
    /**
     * Get executor for a node type
     * 
     * @param nodeType Node type
     * @return Executor
     * @throws UnsupportedNodeTypeException if no executor found
     */
    public NodeExecutor getExecutor(NodeType nodeType) {
        NodeExecutor executor = executors.get(nodeType);
        if (executor == null) {
            throw new UnsupportedNodeTypeException(
                "No executor found for node type: " + nodeType
            );
        }
        return executor;
    }
    
    /**
     * Check if executor exists for a node type
     */
    public boolean hasExecutor(NodeType nodeType) {
        return executors.containsKey(nodeType);
    }
    
    /**
     * Get all registered node types
     */
    public java.util.Set<NodeType> getSupportedNodeTypes() {
        return executors.keySet();
    }
}

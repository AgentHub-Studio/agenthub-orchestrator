package com.agenthub.orchestrator.executor.impl;

import com.agenthub.orchestrator.executor.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that holds pending CompletableFutures for APPROVAL nodes.
 * When a user responds to an approval, the future is completed,
 * allowing the pipeline DAG loop to continue.
 */
@Component
public class ApprovalCompletionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalCompletionRegistry.class);

    private final ConcurrentHashMap<String, CompletableFuture<NodeExecutionResult>> pending
            = new ConcurrentHashMap<>();

    /**
     * Registers a future for an approval node execution.
     *
     * @param key    the composite key "executionId:nodeId"
     * @param future the future to complete when the user responds
     */
    public void register(String key, CompletableFuture<NodeExecutionResult> future) {
        pending.put(key, future);
        logger.info("Registered approval future: {}", key);
    }

    /**
     * Completes the future for the given execution/node when the user responds.
     *
     * @param executionId the execution UUID
     * @param nodeId      the node ID
     * @param approved    whether the user approved
     * @param comment     optional comment from the user
     */
    public void complete(String executionId, String nodeId, boolean approved, String comment) {
        String key = executionId + ":" + nodeId;
        CompletableFuture<NodeExecutionResult> future = pending.remove(key);
        if (future != null) {
            Map<String, Object> output = Map.of(
                    "approved", approved,
                    "comment", comment != null ? comment : ""
            );
            if (approved) {
                future.complete(NodeExecutionResult.success(nodeId, output));
            } else {
                future.complete(NodeExecutionResult.failed(nodeId, "Approval rejected: " + comment));
            }
            logger.info("Completed approval future: key={}, approved={}", key, approved);
        } else {
            logger.warn("No pending approval found for key: {}", key);
        }
    }

    /**
     * Removes a pending future (e.g., on error during approval creation).
     */
    public void remove(String key) {
        pending.remove(key);
    }

    /**
     * Checks if there is a pending approval for the given key.
     */
    public boolean hasPending(String key) {
        return pending.containsKey(key);
    }
}

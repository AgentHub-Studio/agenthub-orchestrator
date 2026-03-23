package com.agenthub.orchestrator.application.executor.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RabbitMQ listener that receives approval response events from the backend.
 * When a user approves or rejects an approval, this listener completes
 * the corresponding CompletableFuture in the ApprovalCompletionRegistry,
 * allowing the pipeline DAG loop to continue.
 */
@Component
public class ApprovalResponseListener {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalResponseListener.class);

    private final ApprovalCompletionRegistry registry;

    public ApprovalResponseListener(ApprovalCompletionRegistry registry) {
        this.registry = registry;
    }

    @RabbitListener(queues = "${agenthub.orchestrator.approval-queue:agenthub.approval.responses}")
    public void onApprovalResponded(Map<String, Object> event) {
        String executionId = String.valueOf(event.get("executionId"));
        String nodeId = String.valueOf(event.get("nodeId"));
        boolean approved = Boolean.TRUE.equals(event.get("approved"));
        String comment = event.get("comment") != null ? String.valueOf(event.get("comment")) : "";

        logger.info("Approval responded: executionId={}, nodeId={}, approved={}",
                executionId, nodeId, approved);

        registry.complete(executionId, nodeId, approved, comment);
    }
}

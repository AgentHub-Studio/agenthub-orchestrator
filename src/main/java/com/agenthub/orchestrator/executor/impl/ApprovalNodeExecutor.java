package com.agenthub.orchestrator.executor.impl;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.executor.ExecutionContext;
import com.agenthub.orchestrator.executor.NodeExecutionResult;
import com.agenthub.orchestrator.executor.NodeExecutor;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for APPROVAL nodes.
 * Pauses the pipeline by returning a CompletableFuture that only completes
 * when the user responds to the approval request via the backend API.
 */
@Component
public class ApprovalNodeExecutor implements NodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalNodeExecutor.class);

    private final ApprovalCompletionRegistry completionRegistry;
    private final WebClient backendClient;

    public ApprovalNodeExecutor(
            ApprovalCompletionRegistry completionRegistry,
            WebClient.Builder builder,
            @Value("${agenthub.backend.base-url:http://agenthub-backend:8080}") String backendUrl) {
        this.completionRegistry = completionRegistry;
        this.backendClient = builder.baseUrl(backendUrl).build();
    }

    @Override
    public NodeType getSupportedType() {
        return NodeType.APPROVAL;
    }

    @Override
    public CompletableFuture<NodeExecutionResult> execute(
            PipelineNode node, ExecutionContext context, Map<String, Object> config) {

        String title = renderTemplate(
                String.valueOf(config.getOrDefault("title", "Approval required")), context);
        String description = renderTemplate(
                String.valueOf(config.getOrDefault("description", "")), context);
        String details = config.get("details") != null ?
                renderTemplate(String.valueOf(config.get("details")), context) : null;
        int timeoutHours = config.get("timeout") instanceof Number n ? n.intValue() : 24;
        String notifyChannels = String.valueOf(config.getOrDefault("notifyChannels", "frontend_push"));
        String webhookCallbackUrl = config.get("webhookCallbackUrl") != null ?
                String.valueOf(config.get("webhookCallbackUrl")) : "";

        // Build approval request
        Map<String, Object> approvalRequest = Map.of(
                "executionId", context.getExecutionId().toString(),
                "nodeId", node.id(),
                "title", title,
                "description", description,
                "details", details != null ? details : "",
                "notifyChannels", List.of(notifyChannels.split(",")),
                "webhookCallbackUrl", webhookCallbackUrl,
                "timeoutHours", timeoutHours
        );

        CompletableFuture<NodeExecutionResult> future = new CompletableFuture<>();

        // Register the future for later completion
        String approvalKey = context.getExecutionId() + ":" + node.id();
        completionRegistry.register(approvalKey, future);

        // Create approval in the backend (which triggers notifications)
        backendClient.post()
                .uri("/api/internal/approvals")
                .header("X-Tenant-Id", context.getTenantId().toString())
                .bodyValue(approvalRequest)
                .retrieve()
                .bodyToMono(Map.class)
                .subscribe(
                        response -> logger.info("Approval created for node {}: {}",
                                node.id(), response.get("id")),
                        error -> {
                            logger.error("Failed to create approval for node {}", node.id(), error);
                            completionRegistry.remove(approvalKey);
                            future.complete(NodeExecutionResult.failed(node.id(),
                                    "Failed to create approval: " + error.getMessage()));
                        }
                );

        // Return the future — it blocks the DAG loop until the user responds
        return future;
    }

    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        List<String> errors = new ArrayList<>();
        if (!config.containsKey("title") || String.valueOf(config.get("title")).isBlank()) {
            errors.add("APPROVAL node config requires 'title'");
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    private String renderTemplate(String template, ExecutionContext context) {
        if (template == null) return "";
        String rendered = template;
        for (Map.Entry<String, Object> entry : context.getInput().entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }
}

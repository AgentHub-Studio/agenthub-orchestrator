package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.executor.NodeExecutor;
import com.agenthub.orchestrator.application.oauth.OAuthCredentialResolver;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for WEBHOOK (outbound) nodes.
 * Sends HTTP notifications to external URLs (Slack, Teams, etc.).
 *
 * Key difference from HttpNodeExecutor: failures are non-blocking.
 * The node always succeeds (fire-and-forget) even if the HTTP request fails.
 */
@Component
public class WebhookOutNodeExecutor implements NodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WebhookOutNodeExecutor.class);

    private final WebClient.Builder webClientBuilder;
    private final OAuthCredentialResolver oauthCredentialResolver;

    public WebhookOutNodeExecutor(WebClient.Builder webClientBuilder,
                                  OAuthCredentialResolver oauthCredentialResolver) {
        this.webClientBuilder = webClientBuilder;
        this.oauthCredentialResolver = oauthCredentialResolver;
    }

    @Override
    public NodeType getSupportedType() {
        return NodeType.WEBHOOK;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<NodeExecutionResult> execute(
            PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        try {
            String urlTemplate = (String) config.get("url");
            String url = renderTemplate(urlTemplate, context);
            String method = String.valueOf(config.getOrDefault("method", "POST")).toUpperCase();
            String bodyTemplate = (String) config.get("body");
            String body = bodyTemplate != null ? renderTemplate(bodyTemplate, context) : null;
            String outputKey = String.valueOf(config.getOrDefault("outputKey", "webhookResponse"));

            Map<String, String> headerConfig = (Map<String, String>) config.getOrDefault("headers", Map.of());
            Map<String, String> allHeaders = new HashMap<>(headerConfig);

            // Optional OAuth
            String credentialId = (String) config.get("oauthCredentialId");
            if (credentialId != null && !credentialId.isBlank()) {
                try {
                    allHeaders.putAll(oauthCredentialResolver
                            .resolveHeaders(credentialId, context.getTenantId()).join());
                } catch (Exception e) {
                    logger.warn("Failed to resolve OAuth for webhook out node {}: {}", node.id(), e.getMessage());
                }
            }

            var request = webClientBuilder.build()
                    .method(HttpMethod.valueOf(method))
                    .uri(url)
                    .headers(h -> allHeaders.forEach(h::add));

            CompletableFuture<NodeExecutionResult> future;
            if (body != null && !body.isBlank()) {
                future = request.contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toEntity(String.class)
                        .map(entity -> {
                            Map<String, Object> output = Map.of(
                                    "success", true,
                                    "statusCode", entity.getStatusCode().value(),
                                    "body", entity.getBody() != null ? entity.getBody() : ""
                            );
                            context.setNodeResult(node.id(), output);
                            return NodeExecutionResult.success(node.id(), output);
                        })
                        .toFuture();
            } else {
                future = request.retrieve()
                        .toEntity(String.class)
                        .map(entity -> {
                            Map<String, Object> output = Map.of(
                                    "success", true,
                                    "statusCode", entity.getStatusCode().value()
                            );
                            context.setNodeResult(node.id(), output);
                            return NodeExecutionResult.success(node.id(), output);
                        })
                        .toFuture();
            }

            // Fire-and-forget: failures don't block the pipeline
            return future.exceptionally(error -> {
                logger.warn("Webhook out node failed (non-blocking): node={}, error={}",
                        node.id(), error.getMessage());
                Map<String, Object> output = Map.of(
                        "success", false,
                        "error", error.getMessage() != null ? error.getMessage() : "Unknown error"
                );
                context.setNodeResult(node.id(), output);
                return NodeExecutionResult.success(node.id(), output);
            });
        } catch (Exception e) {
            logger.error("Failed to execute webhook out node {}", node.id(), e);
            Map<String, Object> output = Map.of("success", false, "error", e.getMessage());
            context.setNodeResult(node.id(), output);
            return CompletableFuture.completedFuture(NodeExecutionResult.success(node.id(), output));
        }
    }

    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        List<String> errors = new ArrayList<>();
        if (!config.containsKey("url") || String.valueOf(config.get("url")).isBlank()) {
            errors.add("WEBHOOK node config requires 'url'");
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    private String renderTemplate(String template, ExecutionContext context) {
        if (template == null) return null;
        String rendered = template;
        for (Map.Entry<String, Object> entry : context.getInput().entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return rendered;
    }
}

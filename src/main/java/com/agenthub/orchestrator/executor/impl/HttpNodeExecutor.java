package com.agenthub.orchestrator.executor.impl;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.executor.ExecutionContext;
import com.agenthub.orchestrator.executor.NodeExecutionResult;
import com.agenthub.orchestrator.executor.NodeExecutor;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for HTTP nodes.
 *
 * Supports outbound HTTP requests with configurable method, URL, headers,
 * body template, and timeout (handled by caller policies).
 *
 * @since 1.0.0
 */
@Component
public class HttpNodeExecutor implements NodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(HttpNodeExecutor.class);
    public static final String NON_RETRYABLE_ERROR_PREFIX = "[NON_RETRYABLE]";

    private final WebClient.Builder webClientBuilder;

    public HttpNodeExecutor(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public NodeType getSupportedType() {
        return NodeType.HTTP;
    }

    @Override
    public CompletableFuture<NodeExecutionResult> execute(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config
    ) {
        try {
            String method = String.valueOf(config.getOrDefault("method", "GET")).toUpperCase();
            String urlTemplate = (String) config.get("url");
            String url = renderTemplate(urlTemplate, context);

            String bodyTemplate = (String) config.get("body");
            String body = bodyTemplate != null ? renderTemplate(bodyTemplate, context) : null;

            @SuppressWarnings("unchecked")
            Map<String, String> headerConfig = (Map<String, String>) config.getOrDefault("headers", Map.of());

            WebClient.RequestBodySpec request = webClientBuilder.build()
                .method(HttpMethod.valueOf(method))
                .uri(url)
                .headers(headers -> applyHeaders(headers, headerConfig, context));

            CompletableFuture<NodeExecutionResult> future;
            if (body != null && !body.isBlank()) {
                future = request
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toEntity(String.class)
                    .map(entity -> toNodeResult(node, context, config, entity.getStatusCode().value(), entity.getHeaders(), entity.getBody()))
                    .toFuture();
            } else {
                future = request
                    .retrieve()
                    .toEntity(String.class)
                    .map(entity -> toNodeResult(node, context, config, entity.getStatusCode().value(), entity.getHeaders(), entity.getBody()))
                    .toFuture();
            }

            return future.exceptionally(error -> {
                Throwable rootCause = unwrap(error);
                logger.error("HTTP node failed: {}", node.id(), rootCause);

                if (rootCause instanceof WebClientResponseException responseException
                    && responseException.getStatusCode().is4xxClientError()) {
                    return NodeExecutionResult.failed(
                        node.id(),
                        NON_RETRYABLE_ERROR_PREFIX + " HTTP " + responseException.getStatusCode().value()
                    );
                }

                return NodeExecutionResult.failed(node.id(), rootCause);
            });
        } catch (Exception e) {
            logger.error("Failed to execute HTTP node {}", node.id(), e);
            return CompletableFuture.completedFuture(NodeExecutionResult.failed(node.id(), e));
        }
    }

    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        List<String> errors = new java.util.ArrayList<>();

        if (!config.containsKey("url") || String.valueOf(config.get("url")).isBlank()) {
            errors.add("HTTP node config requires 'url'");
        }

        if (config.containsKey("method")) {
            String method = String.valueOf(config.get("method")).toUpperCase();
            try {
                HttpMethod.valueOf(method);
            } catch (Exception ignored) {
                errors.add("HTTP node config has invalid 'method': " + method);
            }
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    private NodeExecutionResult toNodeResult(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config,
        int statusCode,
        HttpHeaders headers,
        String body
    ) {
        String outputKey = String.valueOf(config.getOrDefault("outputKey", "response"));

        Map<String, Object> output = new HashMap<>();
        output.put("statusCode", statusCode);
        output.put("headers", headers.toSingleValueMap());
        output.put(outputKey, body);

        context.setNodeResult(node.id(), output);
        return NodeExecutionResult.success(node.id(), output);
    }

    private void applyHeaders(HttpHeaders target, Map<String, String> headerConfig, ExecutionContext context) {
        for (Map.Entry<String, String> entry : headerConfig.entrySet()) {
            String value = renderTemplate(entry.getValue(), context);
            target.add(entry.getKey(), value);
        }
    }

    private String renderTemplate(String template, ExecutionContext context) {
        if (template == null) {
            return null;
        }

        String rendered = template;

        // {{inputKey}} replacement
        for (Map.Entry<String, Object> entry : context.getInput().entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }

        // Simple variable references
        if (rendered.contains("${context.input.text}")) {
            Object inputText = context.resolveVariable("${context.input.text}");
            if (inputText != null) {
                rendered = rendered.replace("${context.input.text}", String.valueOf(inputText));
            }
        }

        return rendered;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}

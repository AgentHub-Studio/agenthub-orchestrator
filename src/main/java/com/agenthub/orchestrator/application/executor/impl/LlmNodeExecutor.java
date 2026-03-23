package com.agenthub.orchestrator.application.executor.impl;

import com.agenthub.orchestrator.domain.node.model.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.application.executor.ExecutionContext;
import com.agenthub.orchestrator.application.executor.NodeExecutionResult;
import com.agenthub.orchestrator.application.executor.NodeExecutor;
import com.agenthub.orchestrator.application.llm.*;
import com.agenthub.orchestrator.application.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LLM executor that uses backend cadastros as source of truth.
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(LlmNodeExecutor.class);
    private static final long DEFAULT_TIMEOUT_MS = 30000L;

    private final LlmConfigResolver llmConfigResolver;
    private final LlmProviderRegistry llmProviderRegistry;

    public LlmNodeExecutor(LlmConfigResolver llmConfigResolver, LlmProviderRegistry llmProviderRegistry) {
        this.llmConfigResolver = llmConfigResolver;
        this.llmProviderRegistry = llmProviderRegistry;
    }

    @Override
    public NodeType getSupportedType() {
        return NodeType.LLM;
    }

    @Override
    public CompletableFuture<NodeExecutionResult> execute(PipelineNode node, ExecutionContext context, Map<String, Object> config) {
        try {
            ResolvedLlmConfig resolved = llmConfigResolver.resolve(context.getTenantId(), config);
            String promptTemplate = (String) config.getOrDefault("prompt", "${context.input.text}");
            String prompt = renderPrompt(promptTemplate, context);
            String systemPrompt = (String) config.get("systemPrompt");
            long timeoutMs = readTimeoutMs(config);

            LlmRequest request = new LlmRequest(
                resolved.modelId(),
                prompt,
                systemPrompt,
                resolved.parameters(),
                buildConnectionConfig(resolved)
            );

            LlmProvider provider = llmProviderRegistry.get(resolved.provider());
            return provider.complete(request)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    String outputKey = (String) config.getOrDefault("outputKey", "response");
                    Map<String, Object> nodeOutput = new HashMap<>();
                    nodeOutput.put(outputKey, response.text());
                    nodeOutput.put("model", response.model());
                    nodeOutput.put("provider", response.provider());
                    context.setNodeResult(node.id(), nodeOutput);
                    return NodeExecutionResult.success(node.id(), nodeOutput);
                })
                .exceptionally(error -> {
                    logger.error("LLM node failed: {}", node.id(), error);
                    return NodeExecutionResult.failed(node.id(), error);
                });
        } catch (Exception e) {
            logger.error("Failed to execute LLM node {}", node.id(), e);
            return CompletableFuture.completedFuture(NodeExecutionResult.failed(node.id(), e));
        }
    }

    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        if (!config.containsKey("prompt")) {
            return ValidationResult.failure(List.of("LLM node config requires 'prompt'"));
        }
        return ValidationResult.success();
    }

    private Map<String, Object> buildConnectionConfig(ResolvedLlmConfig config) {
        Map<String, Object> conn = new HashMap<>();
        conn.put("base_url", config.baseUrl());
        conn.put("api_key", config.apiKey());
        conn.put("organization_id", config.organizationId());
        return conn;
    }

    private long readTimeoutMs(Map<String, Object> config) {
        Object timeout = config.get("timeoutMs");
        if (timeout instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        return DEFAULT_TIMEOUT_MS;
    }

    private String renderPrompt(String template, ExecutionContext context) {
        String rendered = template;
        for (Map.Entry<String, Object> entry : context.getInput().entrySet()) {
            String key = "{{" + entry.getKey() + "}}";
            rendered = rendered.replace(key, String.valueOf(entry.getValue()));
        }
        Object contextInputText = context.resolveVariable("${context.input.text}");
        if (contextInputText != null) {
            rendered = rendered.replace("${context.input.text}", String.valueOf(contextInputText));
        }
        return rendered;
    }
}

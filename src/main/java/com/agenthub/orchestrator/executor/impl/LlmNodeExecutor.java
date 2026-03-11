package com.agenthub.orchestrator.executor.impl;

import com.agenthub.orchestrator.domain.node.NodeType;
import com.agenthub.orchestrator.domain.pipeline.PipelineNode;
import com.agenthub.orchestrator.executor.ExecutionContext;
import com.agenthub.orchestrator.executor.NodeExecutionResult;
import com.agenthub.orchestrator.executor.NodeExecutor;
import com.agenthub.orchestrator.service.llm.*;
import com.agenthub.orchestrator.service.pipeline.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LLM executor that uses backend cadastros as source of truth.
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(LlmNodeExecutor.class);

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

            LlmRequest request = new LlmRequest(
                resolved.modelId(),
                prompt,
                systemPrompt,
                resolved.parameters(),
                buildConnectionConfig(resolved)
            );

            LlmProvider provider = llmProviderRegistry.get(resolved.provider());
            return provider.complete(request)
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

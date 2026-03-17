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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor para nodes TOOL.
 * 
 * <p>Chama o agenthub-skill-runtime para executar skills através de tools.
 * O skill-runtime resolve a skill, seleciona o tool apropriado, valida
 * input/output via JSON Schema e executa o tool.
 * 
 * <p><strong>Configuração do node:</strong>
 * <pre>{@code
 * {
 *   "skillSlug": "document_search",  // Slug da skill a ser executada
 *   "input": {                        // Parametros de input para a skill
 *     "query": "{{inputQuery}}",
 *     "limit": 5
 *   },
 *   "outputKey": "searchResults",    // (Opcional) Chave para salvar resultado
 *   "timeout": 30000                  // (Opcional) Timeout em ms
 * }
 * }</pre>
 * 
 * <p><strong>Propagação de contexto:</strong>
 * - tenantId
 * - userId
 * - agentId
 * - executionId
 * - nodeId
 * 
 * @since 1.0.0
 */
@Component
public class ToolNodeExecutor implements NodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolNodeExecutor.class);
    public static final String NON_RETRYABLE_ERROR_PREFIX = "[NON_RETRYABLE]";

    private final WebClient skillRuntimeClient;

    public ToolNodeExecutor(
        WebClient.Builder webClientBuilder,
        @Value("${agenthub.skill-runtime.base-url:http://agenthub-skill-runtime:8082}") String skillRuntimeBaseUrl
    ) {
        this.skillRuntimeClient = webClientBuilder
            .baseUrl(skillRuntimeBaseUrl)
            .build();
        
        logger.info("ToolNodeExecutor initialized with skill-runtime URL: {}", skillRuntimeBaseUrl);
    }

    @Override
    public NodeType getSupportedType() {
        return NodeType.TOOL;
    }

    @Override
    public CompletableFuture<NodeExecutionResult> execute(
        PipelineNode node,
        ExecutionContext context,
        Map<String, Object> config
    ) {
        try {
            // Extrai configuração
            String skillSlug = (String) config.get("skillSlug");
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) config.get("input");
            String outputKey = (String) config.getOrDefault("outputKey", "toolResult");
            
            // Renderiza inputs com template substitution
            Map<String, Object> renderedInput = renderInputs(input, context);
            
            // Monta request para skill-runtime
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("skillSlug", skillSlug);
            requestBody.put("input", renderedInput);
            
            // Adiciona contexto de execução
            Map<String, Object> executionContext = new HashMap<>();
            executionContext.put("tenantId", context.getTenantId());
            executionContext.put("agentId", context.getAgentId());
            executionContext.put("executionId", context.getExecutionId());
            executionContext.put("nodeId", node.id());
            requestBody.put("context", executionContext);
            
            logger.debug("Invoking skill-runtime: skill={}, nodeId={}, executionId={}", 
                skillSlug, node.id(), context.getExecutionId());
            
            // Chama skill-runtime
            return skillRuntimeClient.post()
                .uri("/api/v1/skills/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> toNodeResult(node, context, response, outputKey))
                .toFuture()
                .exceptionally(error -> handleError(node, error));
                
        } catch (Exception e) {
            logger.error("Falha ao executar TOOL node {}", node.id(), e);
            return CompletableFuture.completedFuture(NodeExecutionResult.failed(node.id(), e));
        }
    }

    @Override
    public ValidationResult validateConfig(Map<String, Object> config) {
        List<String> errors = new java.util.ArrayList<>();

        if (!config.containsKey("skillSlug") || String.valueOf(config.get("skillSlug")).isBlank()) {
            errors.add("TOOL node config requer 'skillSlug'");
        }

        if (!config.containsKey("input")) {
            errors.add("TOOL node config requer 'input'");
        } else if (!(config.get("input") instanceof Map)) {
            errors.add("TOOL node config 'input' deve ser um objeto");
        }

        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    /**
     * Renderiza inputs substituindo templates {{variable}} por valores do contexto.
     */
    private Map<String, Object> renderInputs(Map<String, Object> input, ExecutionContext context) {
        if (input == null) {
            return Map.of();
        }
        
        Map<String, Object> rendered = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object value = entry.getValue();
            
            if (value instanceof String stringValue) {
                // Substitui {{key}} por valores do input
                for (Map.Entry<String, Object> inputEntry : context.getInput().entrySet()) {
                    stringValue = stringValue.replace(
                        "{{" + inputEntry.getKey() + "}}", 
                        String.valueOf(inputEntry.getValue())
                    );
                }
                
                // Substitui ${context.nodeResults.nodeId.key}
                if (stringValue.contains("${context.nodeResults.")) {
                    Object resolved = context.resolveVariable(stringValue);
                    value = resolved != null ? resolved : stringValue;
                } else {
                    value = stringValue;
                }
            }
            
            rendered.put(entry.getKey(), value);
        }
        
        return rendered;
    }

    /**
     * Converte resposta do skill-runtime para NodeExecutionResult.
     */
    private NodeExecutionResult toNodeResult(
        PipelineNode node, 
        ExecutionContext context,
        Map response,
        String outputKey
    ) {
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.get("output");
        
        if (output == null) {
            output = Map.of();
        }
        
        // Salva resultado completo no contexto
        Map<String, Object> nodeResult = new HashMap<>();
        nodeResult.put(outputKey, output);
        nodeResult.put("skillSlug", response.get("skillSlug"));
        nodeResult.put("toolType", response.get("toolType"));
        
        context.setNodeResult(node.id(), nodeResult);
        
        logger.debug("TOOL node executado com sucesso: nodeId={}, skill={}", 
            node.id(), response.get("skillSlug"));
        
        return NodeExecutionResult.success(node.id(), nodeResult);
    }

    /**
     * Trata erros da chamada ao skill-runtime.
     */
    private NodeExecutionResult handleError(PipelineNode node, Throwable error) {
        Throwable rootCause = unwrap(error);
        logger.error("TOOL node falhou: {}", node.id(), rootCause);

        // Erros 4xx são não-retriáveis (input inválido, skill não encontrada, etc)
        if (rootCause instanceof WebClientResponseException responseException
            && responseException.getStatusCode().is4xxClientError()) {
            
            String errorMessage = "Erro ao executar skill: " + responseException.getStatusCode().value();
            
            try {
                String responseBody = responseException.getResponseBodyAsString();
                errorMessage += " - " + responseBody;
            } catch (Exception ignored) {
                // Ignora se não conseguir ler body
            }
            
            return NodeExecutionResult.failed(
                node.id(),
                NON_RETRYABLE_ERROR_PREFIX + " " + errorMessage
            );
        }

        // Erros 5xx são retriáveis (skill-runtime temporariamente indisponível)
        return NodeExecutionResult.failed(node.id(), rootCause);
    }

    /**
     * Unwrap nested exceptions para pegar root cause.
     */
    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}

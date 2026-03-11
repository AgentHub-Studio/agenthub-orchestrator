package com.agenthub.orchestrator.service.llm.provider;

import com.agenthub.orchestrator.service.llm.LlmProvider;
import com.agenthub.orchestrator.service.llm.LlmRequest;
import com.agenthub.orchestrator.service.llm.LlmResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private final WebClient.Builder webClientBuilder;

    public OpenAiCompatibleLlmProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "https://api.openai.com";
        Map<String, Object> payload = Map.of(
            "model", request.modelId(),
            "messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt() != null ? request.systemPrompt() : "You are a helpful assistant."),
                Map.of("role", "user", "content", request.prompt())
            ),
            "temperature", request.getConfig("temperature", 0.7),
            "max_tokens", request.getConfig("max_tokens", 1024)
        );

        return webClientBuilder.baseUrl(baseUrl).build()
            .post()
            .uri("/v1/chat/completions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + request.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(Map.class)
            .map(this::mapResponse)
            .toFuture();
    }

    @Override
    public boolean validateConfig(Map<String, Object> config) {
        return true;
    }

    protected LlmResponse mapResponse(Map<?, ?> response) {
        Object choicesObj = response.get("choices");
        String text = "";
        if (choicesObj instanceof List<?> choices && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> c0) {
            Object messageObj = c0.get("message");
            if (messageObj instanceof Map<?, ?> message) {
                Object content = message.get("content");
                text = content != null ? String.valueOf(content) : "";
            }
        }

        Integer inTokens = null;
        Integer outTokens = null;
        Object usageObj = response.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            inTokens = toInt(usage.get("prompt_tokens"));
            outTokens = toInt(usage.get("completion_tokens"));
        }

        String model = response.get("model") != null ? String.valueOf(response.get("model")) : null;
        return new LlmResponse(text, inTokens, outTokens, model, getProviderName());
    }

    private Integer toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}

package com.agenthub.orchestrator.application.llm.provider;

import com.agenthub.orchestrator.application.llm.LlmProvider;
import com.agenthub.orchestrator.application.llm.LlmRequest;
import com.agenthub.orchestrator.application.llm.LlmResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class AnthropicLlmProvider implements LlmProvider {

    private final WebClient.Builder webClientBuilder;

    public AnthropicLlmProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "https://api.anthropic.com";
        Map<String, Object> payload = Map.of(
            "model", request.modelId(),
            "max_tokens", request.getConfig("max_tokens", 1024),
            "temperature", request.getConfig("temperature", 0.7),
            "system", request.systemPrompt() != null ? request.systemPrompt() : "You are a helpful assistant.",
            "messages", List.of(Map.of("role", "user", "content", request.prompt()))
        );

        return webClientBuilder.baseUrl(baseUrl).build()
            .post()
            .uri("/v1/messages")
            .header("x-api-key", request.getApiKey())
            .header("anthropic-version", "2023-06-01")
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

    private LlmResponse mapResponse(Map<?, ?> response) {
        String text = "";
        Object contentObj = response.get("content");
        if (contentObj instanceof List<?> content && !content.isEmpty() && content.get(0) instanceof Map<?, ?> block) {
            Object txt = block.get("text");
            text = txt != null ? String.valueOf(txt) : "";
        }
        String model = response.get("model") != null ? String.valueOf(response.get("model")) : null;
        return new LlmResponse(text, null, null, model, getProviderName());
    }
}

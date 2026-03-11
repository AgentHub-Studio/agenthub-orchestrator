package com.agenthub.orchestrator.service.llm.provider;

import com.agenthub.orchestrator.service.llm.LlmProvider;
import com.agenthub.orchestrator.service.llm.LlmRequest;
import com.agenthub.orchestrator.service.llm.LlmResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class OllamaLlmProvider implements LlmProvider {

    private final WebClient.Builder webClientBuilder;

    public OllamaLlmProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "http://ollama:11434";
        Map<String, Object> payload = Map.of(
            "model", request.modelId(),
            "prompt", request.prompt(),
            "stream", false,
            "options", request.config() != null ? request.config() : Map.of()
        );

        return webClientBuilder.baseUrl(baseUrl).build()
            .post()
            .uri("/api/generate")
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
        String text = response.get("response") != null ? String.valueOf(response.get("response")) : "";
        String model = response.get("model") != null ? String.valueOf(response.get("model")) : null;
        return new LlmResponse(text, null, null, model, getProviderName());
    }
}

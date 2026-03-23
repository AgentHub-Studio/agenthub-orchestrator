package com.agenthub.orchestrator.application.llm.provider;

import com.agenthub.orchestrator.application.llm.LlmProvider;
import com.agenthub.orchestrator.application.llm.LlmRequest;
import com.agenthub.orchestrator.application.llm.LlmResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class OpenRouterLlmProvider extends OpenAiCompatibleLlmProvider implements LlmProvider {

    public OpenRouterLlmProvider(org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String getProviderName() {
        return "openrouter";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : "https://openrouter.ai/api";
        LlmRequest adjusted = new LlmRequest(
            request.modelId(),
            request.prompt(),
            request.systemPrompt(),
            request.config(),
            Map.of(
                "base_url", baseUrl,
                "api_key", request.getApiKey()
            )
        );
        return super.complete(adjusted);
    }
}

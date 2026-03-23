package com.agenthub.orchestrator.application.llm.provider;

import com.agenthub.orchestrator.application.llm.LlmProvider;
import org.springframework.stereotype.Component;

@Component
public class GenericLlmProvider extends OpenAiCompatibleLlmProvider implements LlmProvider {

    public GenericLlmProvider(org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String getProviderName() {
        return "generic";
    }
}

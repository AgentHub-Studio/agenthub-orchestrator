package com.agenthub.orchestrator.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI/Swagger configuration.
 *
 * @since 1.0.0
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8082}")
    private String serverPort;

    @Bean
    public OpenAPI orchestratorOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("AgentHub Orchestrator API")
                .description("""
                    Pipeline execution engine for the AgentHub platform.
                    
                    ## Features
                    - DAG pipeline execution
                    - Multi-tenancy support
                    - Parallel node execution
                    - Event streaming
                    - Provider-agnostic LLM integration
                    
                    ## Authentication
                    Currently, the API does not require authentication in development mode.
                    Production environments should use tenant-scoped JWT tokens.
                    """)
                .version("1.0.0")
                .license(new License()
                    .name("MIT License")
                    .url("https://opensource.org/licenses/MIT"))
                .contact(new Contact()
                    .name("AgentHub Team")
                    .url("https://github.com/AgentHub-Studio/agenthub-orchestrator")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:" + serverPort)
                    .description("Local development server"),
                new Server()
                    .url("http://localhost:8082")
                    .description("Default orchestrator port")
            ));
    }
}

package com.agenthub.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentHub Orchestrator Application
 * 
 * Responsible for:
 * - Pipeline execution orchestration
 * - DAG traversal and scheduling
 * - Node execution coordination
 * - State management
 * - Event publishing
 * 
 * @version 1.0.0
 * @since 2026-03-11
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

}

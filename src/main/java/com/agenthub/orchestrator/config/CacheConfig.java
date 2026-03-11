package com.agenthub.orchestrator.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Cache configuration for the orchestrator
 * 
 * Caches:
 * - pipelineDefinitions: Pipeline definitions by ID
 * - activePipelines: Active pipelines by agent ID
 * 
 * Using simple in-memory cache for now.
 * TODO: Migrate to Redis in production (Sprint 6+)
 * 
 * @since 1.0.0
 */
@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        
        cacheManager.setCaches(List.of(
            new ConcurrentMapCache("pipelineDefinitions"),
            new ConcurrentMapCache("activePipelines")
        ));
        
        return cacheManager;
    }
}

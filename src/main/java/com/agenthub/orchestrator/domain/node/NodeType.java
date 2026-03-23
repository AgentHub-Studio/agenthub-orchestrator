package com.agenthub.orchestrator.domain.node;

/**
 * Types of nodes in a pipeline DAG
 * 
 * Based on specification: PIPELINE_DAG.md
 * 
 * @since 1.0.0
 */
public enum NodeType {
    
    // ===== Basic Nodes =====
    /**
     * Entry point of pipeline - receives input
     */
    INPUT,
    
    /**
     * Exit point of pipeline - produces output
     */
    OUTPUT,
    
    /**
     * Custom data transformation with sandboxed code
     */
    TRANSFORM,
    
    // ===== AI / RAG Nodes =====
    /**
     * Semantic search in knowledge base
     */
    RETRIEVE,
    
    /**
     * LLM call (OpenAI, Anthropic, Ollama, etc.)
     */
    LLM,
    
    /**
     * Generate embeddings for text
     */
    EMBED,
    
    /**
     * Rerank search results
     */
    RERANK,
    
    // ===== Integration Nodes =====
    /**
     * Execute a skill tool
     */
    TOOL,
    
    /**
     * HTTP request to external API
     */
    HTTP,
    
    /**
     * SQL query execution
     */
    SQL,
    
    /**
     * Webhook call
     */
    WEBHOOK,
    
    // ===== Control Flow Nodes =====
    /**
     * Conditional branch (if-then-else)
     */
    CONDITION,
    
    /**
     * Multi-way conditional (switch-case)
     */
    SWITCH,
    
    /**
     * Iterate over collection
     */
    FOREACH,
    
    /**
     * Split execution into parallel branches
     */
    PARALLEL_SPLIT,
    
    /**
     * Merge results from parallel branches
     */
    MERGE,
    
    /**
     * Delay execution
     */
    DELAY,
    
    /**
     * Retry with backoff
     */
    RETRY,
    
    // ===== Multi-Agent Nodes =====
    /**
     * Call another agent
     */
    CALL_AGENT,
    
    /**
     * Execute subpipeline
     */
    SUBPIPELINE,

    // ===== Human-in-the-Loop Nodes =====
    /**
     * Pause pipeline and wait for human approval
     */
    APPROVAL;
    
    /**
     * Check if node type is a control flow node
     */
    public boolean isControlFlow() {
        return this == CONDITION 
            || this == SWITCH 
            || this == FOREACH 
            || this == PARALLEL_SPLIT 
            || this == MERGE 
            || this == DELAY
            || this == RETRY
            || this == APPROVAL;
    }
    
    /**
     * Check if node type requires external integration
     */
    public boolean isIntegration() {
        return this == TOOL 
            || this == HTTP 
            || this == SQL 
            || this == WEBHOOK;
    }
    
    /**
     * Check if node type uses AI/LLM
     */
    public boolean isAI() {
        return this == LLM 
            || this == RETRIEVE 
            || this == EMBED 
            || this == RERANK;
    }
}

/**
 * Node executors - execute specific node types
 * 
 * Contains:
 * - Basic executors: INPUT, OUTPUT, TRANSFORM
 * - LLM executors: LLM, RETRIEVE, EMBED, RERANK
 * - Tool executors: TOOL, HTTP, SQL, WEBHOOK
 * - Control flow executors: CONDITION, FOREACH, MERGE, RETRY
 * 
 * Each executor implements NodeExecutor interface.
 * Executors are discovered via @NodeExecutor annotation.
 * 
 * @since 1.0.0
 */
package com.agenthub.orchestrator.executor;

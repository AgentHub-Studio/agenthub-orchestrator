# AgentHub Orchestrator

[![Java CI](https://github.com/AgentHub-Studio/agenthub-orchestrator/actions/workflows/ci.yml/badge.svg)](https://github.com/AgentHub-Studio/agenthub-orchestrator/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Pipeline Execution Engine** para a plataforma AgentHub.

## 📋 Descrição

O **Orchestrator** é responsável por:
- Executar pipelines DAG (Directed Acyclic Graph)
- Gerenciar estado de execução
- Orquestrar nodes (INPUT, LLM, TOOL, OUTPUT, etc.)
- Coordenar execução paralela de nodes
- Integrar com Skill Runtime para execução de tools
- Publicar eventos de execução (RabbitMQ)

## 🏗️ Arquitetura

```
┌─────────────────┐
│   Backend API   │
└────────┬────────┘
         │ HTTP: Dispara execução
         ↓
┌─────────────────────────────────┐
│   Agent Execution Service       │
│   - startExecution()            │
│   - getExecutionStatus()        │
└────────┬────────────────────────┘
         │
         ├──→ Pipeline Definition Service (carrega pipeline)
         ├──→ Node Scheduler (identifica nodes prontos)
         ├──→ Node Executor Registry (executa nodes)
         └──→ Event Publisher (publica eventos)

Node Executors:
├── InputNodeExecutor
├── OutputNodeExecutor
├── LlmNodeExecutor (integra com Ollama + LlmConfigResolver)
├── ToolNodeExecutor (chama Skill Runtime)
├── TransformNodeExecutor
└── ... (outros executors)
```

## 🚀 Quick Start

### Pré-requisitos
- Java 21
- Maven 3.9+
- Docker (opcional)

### Desenvolvimento Local

```bash
# Clonar repositório
git clone git@github.com:AgentHub-Studio/agenthub-orchestrator.git
cd agenthub-orchestrator

# Build
mvn clean install

# Rodar
mvn spring-boot:run

# Ou com profile de dev
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Variáveis de Ambiente

| Variável | Descrição | Default |
|----------|-----------|---------|
| SERVER_PORT | Porta do servidor | 8082 |
| BACKEND_URL | URL do backend | http://localhost:8081 |
| SKILL_RUNTIME_URL | URL do skill runtime | http://localhost:8083 |
| OLLAMA_URL | URL do Ollama | http://localhost:11434 |
| RABBITMQ_HOST | Host do RabbitMQ | localhost |
| SPRING_DATASOURCE_URL | URL do PostgreSQL | jdbc:postgresql://localhost:5432/agenthub |

## 📚 API Principal

### POST /api/v1/executions
Inicia execução de um agent.

**Request:**
```json
{
  "agentId": "uuid",
  "input": {
    "query": "Analise este contrato..."
  },
  "mode": "ASYNC"
}
```

**Response:**
```json
{
  "executionId": "uuid",
  "status": "RUNNING"
}
```

### GET /api/v1/executions/{id}
Consulta status de execução.

**Response:**
```json
{
  "executionId": "uuid",
  "status": "COMPLETED",
  "startedAt": "2026-03-10T10:00:00Z",
  "completedAt": "2026-03-10T10:00:05Z",
  "result": {
    "output": "Análise completa..."
  }
}
```

## 🧪 Testes

```bash
# Testes unitários
mvn test

# Testes com coverage
mvn clean test jacoco:report

# Ver relatório
open target/site/jacoco/index.html
```

## 🐳 Docker

```bash
# Build
docker build -t agenthub/orchestrator:latest .

# Run
docker run -p 8082:8082 \
  -e BACKEND_URL=http://backend:8081 \
  -e OLLAMA_URL=http://ollama:11434 \
  agenthub/orchestrator:latest
```

## 📖 Documentação

- [Especificação de Pipelines DAG](https://github.com/AgentHub-Studio/agenthub-middleware/blob/main/docs/spec/PIPELINE_DAG.md)
- [Arquitetura Geral](https://github.com/AgentHub-Studio/agenthub-middleware/blob/main/docs/spec/ARCHITECTURE.md)
- [Roadmap](https://github.com/AgentHub-Studio/agenthub-middleware/blob/main/docs/SPRINTS_V2.md)

## 🤝 Contribuindo

Ver [BRANCHING_STRATEGY.md](https://github.com/AgentHub-Studio/agenthub-infra/blob/main/docs/BRANCHING_STRATEGY.md).

## 📝 Licença

MIT License

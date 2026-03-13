# AgentHub Orchestrator

[![Java CI](https://github.com/AgentHub-Studio/agenthub-orchestrator/actions/workflows/ci.yml/badge.svg)](https://github.com/AgentHub-Studio/agenthub-orchestrator/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

**Pipeline Execution Engine** para a plataforma AgentHub.

## 📋 Descrição

O **Orchestrator** é o motor de execução de pipelines da plataforma AgentHub.

### Características Principais

✅ **Pipeline DAG** - Execução de grafos acíclicos direcionados  
✅ **Multi-tenancy** - Isolamento completo por tenant  
✅ **Execução Paralela** - Nodes independentes executam em paralelo  
✅ **State Management** - Persistência e cache de estado configurável  
✅ **Event Streaming** - Publicação de eventos em tempo real  
✅ **Resiliência** - Timeout, retry e error handling  
✅ **Provider Agnostic** - Suporte a múltiplos LLM providers  
✅ **Observabilidade** - Logs estruturados e métricas

### Responsabilidades

- Executar pipelines DAG (Directed Acyclic Graph)
- Gerenciar estado de execução com cache configurável
- Orquestrar nodes (INPUT, LLM, TRANSFORM, HTTP, OUTPUT)
- Coordenar execução paralela respeitando dependências
- Integrar com backend para resolução de configurações
- Publicar eventos de execução (opcional: RabbitMQ)

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
├── TransformNodeExecutor (JSONPath, templates, jq)
├── HttpNodeExecutor (chamadas HTTP externas)
└── ... (outros executors em desenvolvimento)
```

## 📡 Eventos Publicados

O Orchestrator publica eventos tipados em tempo real:

### Eventos de Execução
- `execution.queued` - Execução adicionada à fila
- `execution.started` - Execução iniciada
- `execution.completed` - Execução concluída com sucesso
- `execution.failed` - Execução falhou
- `execution.cancelled` - Execução cancelada
- `execution.timed_out` - Execução excedeu timeout

### Eventos de Node
- `node.started` - Node iniciou execução
- `node.completed` - Node completou com sucesso
- `node.failed` - Node falhou

**Formato dos eventos:**
```json
{
  "eventType": "execution.completed",
  "payload": {
    "executionId": "9d3e8679-7425-40de-944b-e07fc1f90ae7",
    "tenantId": "550e8400-e29b-41d4-a716-446655440000"
  },
  "timestamp": "2026-03-12T10:00:05Z"
}
```

## 🚀 Quick Start

### Pré-requisitos
- Java 25 (LTS recomendado para produção: Java 21+)
- Maven 3.9+
- Docker (opcional, requerido para build com `build.sh`)

### Desenvolvimento Local

```bash
# Clonar repositório
git clone git@github.com:AgentHub-Studio/agenthub-orchestrator.git
cd agenthub-orchestrator

# Build usando script (recomendado)
./build.sh clean
./build.sh build

# Ou build direto com Maven
mvn clean install

# Rodar aplicação
./build.sh run

# Ou com Maven
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Build com Docker

O projeto inclui script de build que usa Docker para garantir consistência:

```bash
# Build completo (clean + compile + test + package)
./build.sh all

# Apenas testes
./build.sh test

# Build sem testes
./build.sh build

# Executar aplicação
./build.sh run
```

### Variáveis de Ambiente

#### Servidor
| Variável | Descrição | Default |
|----------|-----------|---------|
| `SERVER_PORT` | Porta do servidor | `8082` |
| `SPRING_PROFILES_ACTIVE` | Profile ativo | `dev` |

#### Banco de Dados
| Variável | Descrição | Default |
|----------|-----------|---------|
| `SPRING_DATASOURCE_URL` | URL do PostgreSQL | `jdbc:postgresql://localhost:5432/agenthub` |
| `SPRING_DATASOURCE_USERNAME` | Usuário do banco | `agenthub` |
| `SPRING_DATASOURCE_PASSWORD` | Senha do banco | `agenthub123` |

#### Integrações
| Variável | Descrição | Default |
|----------|-----------|---------|
| `AGENTHUB_BACKEND_BASE_URL` | URL do backend cadastros | `http://agenthub-backend:8080` |
| `AGENTHUB_BACKEND_SERVICE_TOKEN` | Token de serviço | _(vazio)_ |
| `AGENTHUB_BACKEND_REQUEST_TIMEOUT_MS` | Timeout de requisições | `3000` |
| `AGENTHUB_BACKEND_MAX_RETRIES` | Número de retries | `2` |
| `AGENTHUB_BACKEND_RETRY_BACKOFF_MS` | Backoff entre retries | `250` |

#### LLM Providers
| Variável | Descrição | Default |
|----------|-----------|---------|
| `AGENTHUB_LLM_OLLAMA_BASE_URL` | URL do Ollama | `http://ollama:11434` |
| `AGENTHUB_LLM_OLLAMA_TIMEOUT_MS` | Timeout Ollama | `60000` |
| `AGENTHUB_LLM_OPENAI_API_KEY` | API key OpenAI | _(vazio)_ |
| `AGENTHUB_LLM_OPENAI_TIMEOUT_MS` | Timeout OpenAI | `30000` |

#### Eventos
| Variável | Descrição | Default |
|----------|-----------|---------|
| `SPRING_RABBITMQ_HOST` | Host do RabbitMQ | `rabbitmq` |
| `SPRING_RABBITMQ_PORT` | Porta do RabbitMQ | `5672` |
| `SPRING_RABBITMQ_USERNAME` | Usuário RabbitMQ | `agenthub` |
| `SPRING_RABBITMQ_PASSWORD` | Senha RabbitMQ | _(vazio)_ |
| `AGENTHUB_ORCHESTRATOR_EVENTS_ENABLED` | Habilitar eventos | `false` |

#### Execução
| Variável | Descrição | Default |
|----------|-----------|---------|
| `AGENTHUB_EXECUTION_IN_MEMORY_CACHE_ENABLED` | Cache em memória | `true` |
| `AGENTHUB_ORCHESTRATOR_EXECUTION_MAX_PARALLEL_NODES` | Máx. nodes paralelos | `10` |
| `AGENTHUB_ORCHESTRATOR_EXECUTION_NODE_TIMEOUT_MS` | Timeout por node | `300000` |
| `AGENTHUB_ORCHESTRATOR_EXECUTION_EXECUTION_TIMEOUT_MS` | Timeout total | `1800000` |

## 📚 API REST

### POST /v1/executions
Inicia execução de um agent.

**Request:**
```json
{
  "tenantId": "550e8400-e29b-41d4-a716-446655440000",
  "agentId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "agentVersionId": "8f8e8679-7425-40de-944b-e07fc1f90ae7",
  "input": {
    "query": "Analise este contrato de prestação de serviços..."
  },
  "mode": "ASYNC"
}
```

**Response (201 Created):**
```json
{
  "executionId": "9d3e8679-7425-40de-944b-e07fc1f90ae7",
  "status": "QUEUED"
}
```

**Validações:**
- `tenantId` e `agentId` são obrigatórios
- `input` pode ser null (será tratado como objeto vazio)
- `mode` padrão é `ASYNC`

### GET /v1/executions/{executionId}
Consulta status de execução.

**Query Parameters:**
- `tenantId` (required): UUID do tenant

**Response (200 OK):**
```json
{
  "executionId": "9d3e8679-7425-40de-944b-e07fc1f90ae7",
  "status": "COMPLETED",
  "progress": 100,
  "startedAt": "2026-03-12T10:00:00Z",
  "completedAt": "2026-03-12T10:00:05Z"
}
```

**Status possíveis:**
- `QUEUED` - Na fila para execução
- `RUNNING` - Em execução
- `COMPLETED` - Concluído com sucesso
- `FAILED` - Falhou
- `CANCELLED` - Cancelado manualmente
- `TIMED_OUT` - Timeout

### GET /v1/executions
Lista execuções de um tenant.

**Query Parameters:**
- `tenantId` (required): UUID do tenant
- `status` (optional): Filtrar por status
- `page` (optional): Número da página (padrão: 0)
- `size` (optional): Tamanho da página (padrão: 20)

**Response (200 OK):**
```json
{
  "content": [
    {
      "id": "9d3e8679-7425-40de-944b-e07fc1f90ae7",
      "tenantId": "550e8400-e29b-41d4-a716-446655440000",
      "agentId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "status": "COMPLETED",
      "createdAt": "2026-03-12T10:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalElements": 1,
  "totalPages": 1
}
```

### DELETE /v1/executions/{executionId}
Cancela uma execução em andamento.

**Query Parameters:**
- `tenantId` (required): UUID do tenant

**Response (204 No Content)**

### Tratamento de Erros

Todos os endpoints retornam erros no formato padrão:

**400 Bad Request (Validação):**
```json
{
  "error": "validation_error",
  "message": "Invalid request payload",
  "details": [
    "tenantId: must not be null",
    "agentId: must not be null"
  ]
}
```

**404 Not Found:**
```json
{
  "error": "execution_not_found",
  "message": "Execution not found: 9d3e8679-7425-40de-944b-e07fc1f90ae7"
}
```

**502 Bad Gateway (Backend indisponível):**
```json
{
  "error": "backend_settings_unavailable",
  "message": "Failed to fetch backend settings"
}
```

## 💡 Exemplos de Uso

### Exemplo 1: Execução Simples

```bash
curl -X POST http://localhost:8082/v1/executions \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "550e8400-e29b-41d4-a716-446655440000",
    "agentId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "input": {
      "text": "Olá, mundo!"
    }
  }'
```

### Exemplo 2: Consultar Status

```bash
EXECUTION_ID="9d3e8679-7425-40de-944b-e07fc1f90ae7"
TENANT_ID="550e8400-e29b-41d4-a716-446655440000"

curl "http://localhost:8082/v1/executions/${EXECUTION_ID}?tenantId=${TENANT_ID}"
```

### Exemplo 3: Listar Execuções

```bash
curl "http://localhost:8082/v1/executions?tenantId=550e8400-e29b-41d4-a716-446655440000&status=COMPLETED&page=0&size=10"
```

### Exemplo 4: Cancelar Execução

```bash
curl -X DELETE "http://localhost:8082/v1/executions/${EXECUTION_ID}?tenantId=${TENANT_ID}"
```

## 🧪 Testes

```bash
# Testes unitários
./build.sh test

# Ou com Maven
mvn test

# Testes com coverage
mvn clean test jacoco:report

# Ver relatório de coverage
open target/site/jacoco/index.html
```

**Resultados atuais:**
- ✅ 43+ testes unitários
- ✅ Cobertura de código > 75%
- ✅ Testes de integração com Testcontainers
- ✅ Testes de contrato de API sem Mockito (Java 25 compatível)

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

- [Especificação canônica de produto](https://github.com/AgentHub-Studio/agenthub-middleware/blob/main/docs/spec/AGENTS.md)
- [Planejamento técnico (skill plan-task)](https://github.com/AgentHub-Studio/agenthub-middleware/blob/main/docs/.claude/skills/plan-task/SKILL.md)
- [Roadmap](https://github.com/AgentHub-Studio/agenthub-middleware/blob/main/docs/SPRINTS_V2.md)

## 🤝 Contribuindo

Ver [BRANCHING_STRATEGY.md](https://github.com/AgentHub-Studio/agenthub-infra/blob/main/docs/BRANCHING_STRATEGY.md).

## 📝 Licença

MIT License

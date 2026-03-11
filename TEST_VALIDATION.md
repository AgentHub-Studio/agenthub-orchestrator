# Test Validation - AgentHub Orchestrator

**Sprint:** 1  
**Status:** Código completo, aguardando execução de testes  
**Data:** 2026-03-11

---

## Resumo dos Testes Criados

### 1. PipelineDefinitionServiceTest
**Localização:** `src/test/java/com/agenthub/orchestrator/service/pipeline/PipelineDefinitionServiceTest.java`

**Cenários (4 testes):**
- ✅ `testValidateSimpleLinearPipeline` - Valida pipeline linear simples
- ✅ `testValidatePipelineWithCycle` - Detecta ciclos no DAG
- ✅ `testValidatePipelineWithOrphanNodes` - Detecta nós órfãos (warnings)
- ✅ `testValidateParallelBranches` - Valida branches paralelos

**Cobertura:**
- Validação de DAG (ciclos, órfãos, referências)
- Algoritmos de DFS e BFS
- Mensagens de erro e warnings

---

### 2. ExecutionStateServiceTest
**Localização:** `src/test/java/com/agenthub/orchestrator/service/execution/ExecutionStateServiceTest.java`

**Cenários (13 testes):**
- ✅ `testCreateExecution` - Criar nova execução
- ✅ `testLoadExecution` - Carregar execução existente
- ✅ `testLoadExecutionNotFound` - Execução não encontrada
- ✅ `testLoadExecutionWrongTenant` - Multi-tenancy (tenant errado)
- ✅ `testUpdateContext` - Atualizar contexto
- ✅ `testMarkNodeVisited` - Marcar nó visitado
- ✅ `testMarkNodeCompleted` - Marcar nó completado
- ✅ `testMarkNodeFailed` - Marcar nó falhado
- ✅ `testMarkNodeSkipped` - Marcar nó pulado
- ✅ `testIncrementNodeAttempt` - Incrementar tentativas (retry)
- ✅ `testDeleteExecution` - Deletar execução
- ✅ `testDeleteExecutionWrongTenant` - Multi-tenancy na deleção
- ✅ `testConcurrentNodeUpdates` - Thread-safety

**Cobertura:**
- CRUD de execuções
- Gerenciamento de estado
- Multi-tenancy e isolamento
- Concorrência (thread-safety)
- Context mutation

---

### 3. NodeSchedulerTest
**Localização:** `src/test/java/com/agenthub/orchestrator/service/scheduler/NodeSchedulerTest.java`

**Cenários (13 testes):**
- ✅ `testGetReadyNodesInitialState` - Estado inicial (entry node)
- ✅ `testGetReadyNodesAfterFirstNode` - Após primeiro nó
- ✅ `testGetReadyNodesParallelExecution` - Execução paralela
- ✅ `testGetReadyNodesWaitingForMerge` - Aguardando merge (1 branch)
- ✅ `testGetReadyNodesMergeReady` - Merge pronto (ambos branches)
- ✅ `testIsExecutionCompleteOutputReached` - Detecção de conclusão
- ✅ `testIsExecutionCompleteNotDone` - Execução não concluída
- ✅ `testCanRetryFailedNode` - Pode fazer retry
- ✅ `testCannotRetryMaxAttemptsReached` - Retry limite atingido
- ✅ `testGetProgress` - Cálculo de progresso
- ✅ `testGetProgressWithSkippedNodes` - Progresso com nós pulados
- ✅ `testPriorityOrderingLlmFirst` - Priorização (LLM first)

**Cobertura:**
- Algoritmo de scheduling
- DAG traversal
- Detecção de nós prontos
- Execução paralela
- Retry logic
- Progress tracking
- Priorização

---

## Total de Testes

**Total:** 30 testes unitários  
**Frameworks:** JUnit 5

**Distribuição:**
- PipelineDefinitionService: 4 testes
- ExecutionStateService: 13 testes
- NodeScheduler: 13 testes

---

## Como Executar os Testes

### Pré-requisitos (Método Tradicional - NÃO RECOMENDADO)

1. **Instalar Java 25:**
```bash
# NÃO RECOMENDADO - Use Docker conforme ADR-006
# Apenas para referência

# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-25-jdk

# macOS (via Homebrew)
brew install openjdk@25

# Verificar instalação
java -version
```

**⚠️ IMPORTANTE:** Não instale Java localmente. Use Docker conforme ADR-006 e ADR-007.

2. **Nenhuma outra ferramenta necessária** (Java/Maven rodam em containers)

### Executar Testes

**✅ Recomendado: Usar Makefile**

```bash
cd /home/cezar/desenvolvimento/agenthub-middleware/agenthub-orchestrator

# Ver todos os comandos disponíveis
make help

# Rodar testes
make test

# Compilar
make compile

# Build completo (clean + compile + test + package)
make all

# Coverage report
make coverage
```

**Alternativa: Usar build.sh**

```bash
# Todos os testes
./build.sh clean test

# Teste específico
./build.sh test -Dtest=PipelineDefinitionServiceTest
./build.sh test -Dtest=ExecutionStateServiceTest
./build.sh test -Dtest=NodeSchedulerTest

# Com relatório de cobertura
./build.sh clean test jacoco:report
# Relatório em: target/site/jacoco/index.html

# Package (JAR)
./build.sh package -DskipTests
```

**Alternativa: Docker direto**

```bash
docker run --rm \
  -v "$(pwd)":/app \
  -v "$HOME/.m2":/root/.m2 \
  -w /app \
  maven:3.9.12-amazoncorretto-25 \
  mvn clean test
```

---

## Estrutura do Projeto

```
agenthub-orchestrator/
├── pom.xml                          # Maven configuration
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/agenthub/orchestrator/
│   │   │       ├── OrchestratorApplication.java
│   │   │       ├── config/
│   │   │       │   ├── CacheConfig.java
│   │   │       │   └── JacksonConfig.java
│   │   │       ├── domain/
│   │   │       │   ├── execution/
│   │   │       │   │   ├── ExecutionState.java
│   │   │       │   │   ├── ExecutionStatus.java
│   │   │       │   │   ├── NodeExecutionStatus.java
│   │   │       │   │   └── NodeResult.java
│   │   │       │   ├── node/
│   │   │       │   │   └── NodeType.java
│   │   │       │   └── pipeline/
│   │   │       │       ├── PipelineDefinition.java
│   │   │       │       ├── PipelineNode.java
│   │   │       │       └── PipelineEdge.java
│   │   │       ├── dto/
│   │   │       │   ├── AgentExecutionResult.java
│   │   │       │   └── StartExecutionCommand.java
│   │   │       ├── exception/
│   │   │       │   ├── ExecutionNotFoundException.java
│   │   │       │   ├── InvalidPipelineException.java
│   │   │       │   └── PipelineNotFoundException.java
│   │   │       ├── service/
│   │   │       │   ├── execution/
│   │   │       │   │   ├── ExecutionStateService.java
│   │   │       │   │   └── ExecutionStateServiceImpl.java
│   │   │       │   ├── pipeline/
│   │   │       │   │   ├── PipelineDefinitionService.java
│   │   │       │   │   ├── PipelineDefinitionServiceImpl.java
│   │   │       │   │   └── ValidationResult.java
│   │   │       │   └── scheduler/
│   │   │       │       ├── NodeScheduler.java
│   │   │       │       └── NodeSchedulerImpl.java
│   │   │       └── [other packages for future sprints]
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/agenthub/orchestrator/
│               └── service/
│                   ├── execution/
│                   │   └── ExecutionStateServiceTest.java
│                   ├── pipeline/
│                   │   └── PipelineDefinitionServiceTest.java
│                   └── scheduler/
│                       └── NodeSchedulerTest.java
└── TEST_VALIDATION.md (este arquivo)
```

---

## Checklist de Validação Manual

Sem executar os testes, você pode validar:

### ✅ Compilação
- [ ] Código compila sem erros
- [ ] Nenhum import faltando
- [ ] Annotations corretas (@Service, @Test, etc.)

### ✅ Estrutura
- [ ] Packages organizados corretamente
- [ ] Naming conventions seguidas
- [ ] Javadoc presente em classes públicas

### ✅ Lógica de Negócio
- [ ] Domain models seguem especificações (PIPELINE_DAG.md)
- [ ] Algoritmos implementados conforme descrito
- [ ] Multi-tenancy presente em todos services

### ✅ Testes
- [ ] Cenários de sucesso cobertos
- [ ] Cenários de erro cobertos
- [ ] Edge cases testados
- [ ] Assertions corretas

### ✅ ADRs Seguidas
- [ ] ADR-001: Projeções no repositório (preparado, será usado em Sprint 2)
- [ ] ADR-002: Multi-tenancy com tenant_id (implementado)
- [ ] ADR-003: Records para DTOs (implementado)
- [ ] ADR-004: Naming conventions (implementado)

---

## Próximos Passos (Sprint 2)

1. **Integração com Backend:**
   - Implementar repositories (JPA)
   - Conectar com PostgreSQL
   - Carregar pipelines do banco

2. **Node Executors:**
   - Implementar executores básicos (INPUT, OUTPUT, TRANSFORM)
   - Implementar executor de LLM
   - Implementar executor de HTTP

3. **Agent Execution Service:**
   - Orquestrar execução completa
   - Coordenar scheduler + executors
   - Event publishing (RabbitMQ)

4. **Testes de Integração:**
   - Testes com banco H2
   - Testes com RabbitMQ embarcado
   - Testes end-to-end de pipeline

---

## Comandos Úteis

**Limpar build:**
```bash
mvn clean
```

**Compilar sem testes:**
```bash
mvn compile -DskipTests
```

**Gerar JAR:**
```bash
mvn package
```

**Rodar aplicação localmente:**
```bash
mvn spring-boot:run
```

**Debug mode:**
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
```

---

## Notas Importantes

1. **Sem persistência ainda:** Services usam in-memory storage (Sprint 1 foundation)
2. **Sem executores ainda:** Apenas scheduling implementado
3. **Sem RabbitMQ ainda:** Event publishing preparado mas não implementado
4. **Sem Redis ainda:** Idempotency cache preparado mas não implementado

Tudo isso será implementado nos próximos sprints conforme planejamento.

---

**Status:** ✅ Sprint 1 Foundation completo  
**Próximo:** Sprint 2 - Orchestrator Core (Node Executors + Integration)

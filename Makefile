# ===================================================================
# AgentHub Orchestrator - Makefile
# ===================================================================
# All commands run in Docker containers for consistency
# ===================================================================

.PHONY: help compile test package clean run validate install

# Docker image
MAVEN_IMAGE := maven:3.9.12-amazoncorretto-25
DOCKER_RUN := docker run --rm -v $(PWD):/app -v $(HOME)/.m2:/root/.m2 -w /app $(MAVEN_IMAGE)

# Default target
.DEFAULT_GOAL := help

help: ## Show this help message
	@echo "AgentHub Orchestrator - Build Commands"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

compile: ## Compile source code
	@echo "Compiling source code..."
	$(DOCKER_RUN) mvn compile

test: ## Run unit tests
	@echo "Running tests..."
	$(DOCKER_RUN) mvn test

verify: ## Run tests with verification
	@echo "Running tests with verification..."
	$(DOCKER_RUN) mvn verify

package: ## Build JAR (skip tests)
	@echo "Building JAR..."
	$(DOCKER_RUN) mvn package -DskipTests

package-with-tests: ## Build JAR (with tests)
	@echo "Building JAR with tests..."
	$(DOCKER_RUN) mvn package

clean: ## Clean build artifacts
	@echo "Cleaning..."
	$(DOCKER_RUN) mvn clean

install: ## Install dependencies
	@echo "Installing dependencies..."
	$(DOCKER_RUN) mvn install -DskipTests

validate: ## Validate project structure
	@echo "Validating project..."
	@./validate-structure.sh

run: ## Run Spring Boot application
	@echo "Running application..."
	$(DOCKER_RUN) mvn spring-boot:run

run-debug: ## Run with debug enabled (port 5005)
	@echo "Running in debug mode (port 5005)..."
	$(DOCKER_RUN) mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=*:5005"

dependency-tree: ## Show dependency tree
	$(DOCKER_RUN) mvn dependency:tree

dependency-updates: ## Check for dependency updates
	$(DOCKER_RUN) mvn versions:display-dependency-updates

format: ## Format code
	$(DOCKER_RUN) mvn fmt:format

lint: ## Check code style
	$(DOCKER_RUN) mvn fmt:check

coverage: ## Generate test coverage report
	@echo "Generating coverage report..."
	$(DOCKER_RUN) mvn clean test jacoco:report
	@echo "Report available at: target/site/jacoco/index.html"

all: clean compile test package ## Clean, compile, test, and package

ci: clean verify ## CI pipeline (clean + verify)
	@echo "✓ CI pipeline completed"

#!/bin/bash

# Script to validate project structure without running tests
# Can be run without Java/Maven installed

echo "=========================================="
echo "AgentHub Orchestrator - Structure Validation"
echo "Sprint 1 - Foundation"
echo "=========================================="
echo ""

PROJECT_ROOT="/home/cezar/desenvolvimento/agenthub-middleware/agenthub-orchestrator"
cd "$PROJECT_ROOT"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ERRORS=0
WARNINGS=0

# Function to check file exists
check_file() {
    if [ -f "$1" ]; then
        echo -e "${GREEN}✓${NC} $1"
        return 0
    else
        echo -e "${RED}✗${NC} $1 - NOT FOUND"
        ((ERRORS++))
        return 1
    fi
}

# Function to check directory exists
check_dir() {
    if [ -d "$1" ]; then
        echo -e "${GREEN}✓${NC} $1/"
        return 0
    else
        echo -e "${RED}✗${NC} $1/ - NOT FOUND"
        ((ERRORS++))
        return 1
    fi
}

# Function to count Java files
count_java_files() {
    local count=$(find "$1" -name "*.java" 2>/dev/null | wc -l)
    echo "$count"
}

echo "1. Project Configuration"
echo "------------------------"
check_file "pom.xml"
check_file "README.md"
check_file "TEST_VALIDATION.md"
echo ""

echo "2. Source Structure"
echo "-------------------"
check_dir "src/main/java"
check_dir "src/main/resources"
check_dir "src/test/java"
echo ""

echo "3. Main Application"
echo "-------------------"
check_file "src/main/java/com/agenthub/orchestrator/OrchestratorApplication.java"
check_file "src/main/resources/application.yml"
echo ""

echo "4. Configuration Classes"
echo "------------------------"
check_file "src/main/java/com/agenthub/orchestrator/config/CacheConfig.java"
check_file "src/main/java/com/agenthub/orchestrator/config/JacksonConfig.java"
echo ""

echo "5. Domain Models"
echo "----------------"
check_file "src/main/java/com/agenthub/orchestrator/domain/execution/ExecutionState.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/execution/ExecutionStatus.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/execution/NodeExecutionStatus.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/execution/NodeResult.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/node/NodeType.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/pipeline/PipelineDefinition.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/pipeline/PipelineNode.java"
check_file "src/main/java/com/agenthub/orchestrator/domain/pipeline/PipelineEdge.java"
echo ""

echo "6. DTOs"
echo "-------"
check_file "src/main/java/com/agenthub/orchestrator/dto/AgentExecutionResult.java"
check_file "src/main/java/com/agenthub/orchestrator/dto/StartExecutionCommand.java"
echo ""

echo "7. Exceptions"
echo "-------------"
check_file "src/main/java/com/agenthub/orchestrator/exception/ExecutionNotFoundException.java"
check_file "src/main/java/com/agenthub/orchestrator/exception/InvalidPipelineException.java"
check_file "src/main/java/com/agenthub/orchestrator/exception/PipelineNotFoundException.java"
echo ""

echo "8. Services"
echo "-----------"
check_file "src/main/java/com/agenthub/orchestrator/service/execution/ExecutionStateService.java"
check_file "src/main/java/com/agenthub/orchestrator/service/execution/ExecutionStateServiceImpl.java"
check_file "src/main/java/com/agenthub/orchestrator/service/pipeline/PipelineDefinitionService.java"
check_file "src/main/java/com/agenthub/orchestrator/service/pipeline/PipelineDefinitionServiceImpl.java"
check_file "src/main/java/com/agenthub/orchestrator/service/pipeline/ValidationResult.java"
check_file "src/main/java/com/agenthub/orchestrator/service/scheduler/NodeScheduler.java"
check_file "src/main/java/com/agenthub/orchestrator/service/scheduler/NodeSchedulerImpl.java"
echo ""

echo "9. Test Files"
echo "-------------"
check_file "src/test/java/com/agenthub/orchestrator/service/execution/ExecutionStateServiceTest.java"
check_file "src/test/java/com/agenthub/orchestrator/service/pipeline/PipelineDefinitionServiceTest.java"
check_file "src/test/java/com/agenthub/orchestrator/service/scheduler/NodeSchedulerTest.java"
echo ""

echo "10. Statistics"
echo "--------------"
MAIN_JAVA_COUNT=$(count_java_files "src/main/java")
TEST_JAVA_COUNT=$(count_java_files "src/test/java")
TOTAL_JAVA_COUNT=$((MAIN_JAVA_COUNT + TEST_JAVA_COUNT))

echo "Main Java files: $MAIN_JAVA_COUNT"
echo "Test Java files: $TEST_JAVA_COUNT"
echo "Total Java files: $TOTAL_JAVA_COUNT"
echo ""

# Check for common issues
echo "11. Code Quality Checks"
echo "-----------------------"

# Check for TODO comments
TODO_COUNT=$(grep -r "TODO" src/main/java 2>/dev/null | wc -l)
echo "TODO comments: $TODO_COUNT (documented for future sprints)"

# Check for System.out.println (should use logger)
SYSOUT_COUNT=$(grep -r "System.out.println" src/main/java 2>/dev/null | wc -l)
if [ "$SYSOUT_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠${NC} System.out.println found: $SYSOUT_COUNT (should use logger)"
    ((WARNINGS++))
else
    echo -e "${GREEN}✓${NC} No System.out.println found"
fi

# Check for printStackTrace (should use logger)
PRINTSTACKTRACE_COUNT=$(grep -r "printStackTrace" src/main/java 2>/dev/null | wc -l)
if [ "$PRINTSTACKTRACE_COUNT" -gt 0 ]; then
    echo -e "${YELLOW}⚠${NC} printStackTrace found: $PRINTSTACKTRACE_COUNT (should use logger)"
    ((WARNINGS++))
else
    echo -e "${GREEN}✓${NC} No printStackTrace found"
fi

echo ""
echo "=========================================="
echo "Validation Summary"
echo "=========================================="
echo -e "Errors: ${RED}$ERRORS${NC}"
echo -e "Warnings: ${YELLOW}$WARNINGS${NC}"
echo ""

if [ "$ERRORS" -eq 0 ]; then
    echo -e "${GREEN}✓ Structure validation PASSED${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Install Java 21: sudo apt install openjdk-21-jdk"
    echo "2. Install Maven: sudo apt install maven"
    echo "3. Run tests: mvn clean test"
    exit 0
else
    echo -e "${RED}✗ Structure validation FAILED${NC}"
    echo "Please fix the errors above."
    exit 1
fi

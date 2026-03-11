#!/bin/bash

# ===================================================================
# AgentHub Orchestrator - Build Script (Docker-based)
# ===================================================================
# Uses Docker to ensure consistent builds across environments
# Image: maven:3.9.12-amazoncorretto-25
# ===================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="agenthub-orchestrator"
MAVEN_IMAGE="maven:3.9.12-amazoncorretto-25"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}AgentHub Orchestrator - Build${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${YELLOW}Error: Docker is not running${NC}"
    echo "Please start Docker and try again"
    exit 1
fi

# Create .m2 cache directory if it doesn't exist
if [ ! -d "$HOME/.m2" ]; then
    echo -e "${YELLOW}Creating Maven cache directory: $HOME/.m2${NC}"
    mkdir -p "$HOME/.m2"
fi

echo -e "${GREEN}Running Maven in Docker...${NC}"
echo -e "Image: ${MAVEN_IMAGE}"
echo -e "Command: mvn $@"
echo ""

# Run Maven in Docker with cache volume
docker run --rm \
    -v "${SCRIPT_DIR}":/app \
    -v "$HOME/.m2":/root/.m2 \
    -w /app \
    ${MAVEN_IMAGE} \
    mvn "$@"

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✓ Build completed successfully${NC}"
else
    echo ""
    echo -e "${YELLOW}✗ Build failed with exit code: $EXIT_CODE${NC}"
    exit $EXIT_CODE
fi

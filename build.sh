#!/usr/bin/env bash
set -euo pipefail

GO_IMAGE="golang:1.25-alpine"
MODULE_CACHE_VOLUME="${AGENTHUB_GO_CACHE_VOLUME:-agenthub-orchestrator-go-cache}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMMONS_DIR="${AGENTHUB_GO_COMMONS_DIR:-$(dirname "${SCRIPT_DIR}")/agenthub-go-commons}"

if [[ ! -d "${COMMONS_DIR}" ]]; then
  echo "agenthub-go-commons not found: ${COMMONS_DIR}" >&2
  exit 1
fi

run_go() {
  docker run --rm \
    -v "${SCRIPT_DIR}":/app \
    -v "${COMMONS_DIR}":/agenthub-go-commons:ro \
    -v "${MODULE_CACHE_VOLUME}":/go/pkg/mod \
    -w /app \
    "${GO_IMAGE}" \
    "$@"
}

command="${1:-help}"
shift || true

case "${command}" in
  compile)
    run_go go build ./...
    ;;
  test)
    docker run --rm \
      -v "${SCRIPT_DIR}":/app \
      -v "${COMMONS_DIR}":/agenthub-go-commons:ro \
      -v "${MODULE_CACHE_VOLUME}":/go/pkg/mod \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -e CGO_ENABLED=1 \
      -w /app \
      "${GO_IMAGE}" \
      sh -c 'apk add --no-cache gcc musl-dev && go test -v -race -coverprofile=coverage.out ./... "$@"' -- "$@"
    ;;
  package)
    docker build \
      --build-context "gocommons=${COMMONS_DIR}" \
      -t "agenthub-studio/agenthub-orchestrator:local" \
      "$@" \
      "${SCRIPT_DIR}"
    ;;
  lint)
    docker run --rm \
      -v "${SCRIPT_DIR}":/app \
      -v "${COMMONS_DIR}":/agenthub-go-commons:ro \
      -v "${MODULE_CACHE_VOLUME}":/go/pkg/mod \
      -w /app \
      golangci/golangci-lint:latest golangci-lint run ./...
    ;;
  tidy)
    run_go go mod tidy
    ;;
  help|*)
    echo "Usage: ./build.sh [compile|test|package|lint|tidy]"
    ;;
esac

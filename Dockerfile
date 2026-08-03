FROM golang:1.25.12-alpine AS builder
RUN apk add --no-cache git ca-certificates tzdata
COPY --from=gocommons . /agenthub-go-commons
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-w -s -extldflags '-static'" \
    -o /build/bin/orchestrator \
    ./cmd/orchestrator

FROM scratch
ARG OCI_CREATED="unknown"
ARG OCI_REVISION="unknown"
ARG OCI_SOURCE="https://github.com/AgentHub-Studio/agenthub-orchestrator"
ARG OCI_VERSION="local"
LABEL org.opencontainers.image.title="agenthub-orchestrator" \
    org.opencontainers.image.description="AgentHub orchestrator service" \
    org.opencontainers.image.source="${OCI_SOURCE}" \
    org.opencontainers.image.revision="${OCI_REVISION}" \
    org.opencontainers.image.created="${OCI_CREATED}" \
    org.opencontainers.image.version="${OCI_VERSION}" \
    org.opencontainers.image.vendor="AgentHub Studio" \
    org.opencontainers.image.licenses="Proprietary"
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo
COPY --from=builder /build/bin/orchestrator /orchestrator
USER 65532:65532
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["/orchestrator", "-health"] || exit 1
ENTRYPOINT ["/orchestrator"]

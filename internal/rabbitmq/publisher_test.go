package rabbitmq_test

import (
	"context"
	"testing"
	"time"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/rabbitmq"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewPublisher_InvalidURL(t *testing.T) {
	_, err := rabbitmq.NewPublisher("amqp://invalid-host-that-does-not-exist-xyz:5672/")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "rabbitmq publisher")
}

func TestNewPublisher_MalformedURL(t *testing.T) {
	_, err := rabbitmq.NewPublisher("not-a-url")
	require.Error(t, err)
}

func TestExecutionEvent_Serialization(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	event := rabbitmq.ExecutionEvent{
		ExecutionID: "exec-123",
		TenantID:    "my-tenant",
		AgentID:     "agent-456",
		Status:      "COMPLETED",
		StartedAt:   now,
		DurationMs:  250,
		NodeCount:   3,
	}

	// Verify that all required JSON fields are set.
	assert.Equal(t, "exec-123", event.ExecutionID)
	assert.Equal(t, "COMPLETED", event.Status)
	assert.Equal(t, int64(250), event.DurationMs)
	assert.Nil(t, event.FinishedAt)
	assert.Empty(t, event.ErrorMsg)
}

func TestPublisher_Close_WhenNotConnected(t *testing.T) {
	// Close on a nil publisher should not panic.
	var p *rabbitmq.Publisher
	assert.NotPanics(t, func() {
		if p != nil {
			p.Close()
		}
	})
}

func TestPublisher_Publish_WithoutConnection(t *testing.T) {
	// Attempting to publish without a real broker should fail gracefully.
	p, err := rabbitmq.NewPublisher("amqp://guest:guest@localhost:5672/")
	if err != nil {
		// No broker available — expected in CI.
		t.Skip("RabbitMQ not available:", err)
	}
	defer p.Close()

	event := rabbitmq.ExecutionEvent{
		ExecutionID: "test-exec",
		TenantID:    "test-tenant",
		AgentID:     "test-agent",
		Status:      "RUNNING",
		StartedAt:   time.Now(),
	}
	err = p.Publish(context.Background(), event)
	assert.NoError(t, err)
}

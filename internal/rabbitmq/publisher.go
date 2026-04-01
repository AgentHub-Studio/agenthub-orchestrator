// Package rabbitmq provides an AMQP publisher for execution lifecycle events.
package rabbitmq

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

const (
	exchangeName    = "executions"
	exchangeType    = "fanout"
	routingKey      = ""
	reconnectDelay  = 3 * time.Second
	publishTimeout  = 5 * time.Second
)

// ExecutionEvent is the event published when an execution changes state.
// Schema matches the observability consumer (consumer.ExecutionEvent).
type ExecutionEvent struct {
	ExecutionID string     `json:"executionId"`
	TenantID    string     `json:"tenantId"`
	AgentID     string     `json:"agentId"`
	Status      string     `json:"status"` // PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
	StartedAt   time.Time  `json:"startedAt"`
	FinishedAt  *time.Time `json:"finishedAt,omitempty"`
	DurationMs  int64      `json:"durationMs,omitempty"`
	NodeCount   int        `json:"nodeCount,omitempty"`
	ErrorMsg    string     `json:"errorMsg,omitempty"`
}

// Publisher publishes execution events to RabbitMQ.
// It reconnects automatically when the connection drops.
type Publisher struct {
	url  string
	conn *amqp.Connection
	ch   *amqp.Channel
}

// NewPublisher dials RabbitMQ and returns a Publisher ready to publish.
// Returns an error only when the initial dial fails.
func NewPublisher(url string) (*Publisher, error) {
	p := &Publisher{url: url}
	if err := p.connect(); err != nil {
		return nil, fmt.Errorf("rabbitmq publisher: %w", err)
	}
	return p, nil
}

// Publish serialises event to JSON and pushes it to the executions exchange.
// If the channel is closed it attempts a single reconnect before giving up.
func (p *Publisher) Publish(ctx context.Context, event ExecutionEvent) error {
	body, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("rabbitmq publisher: marshal event: %w", err)
	}

	pubCtx, cancel := context.WithTimeout(ctx, publishTimeout)
	defer cancel()

	err = p.ch.PublishWithContext(pubCtx, exchangeName, routingKey, false, false,
		amqp.Publishing{
			ContentType:  "application/json",
			DeliveryMode: amqp.Persistent,
			Timestamp:    time.Now(),
			Body:         body,
		})
	if err != nil {
		slog.Warn("rabbitmq publish failed, attempting reconnect", "err", err)
		if reconnErr := p.connect(); reconnErr != nil {
			return fmt.Errorf("rabbitmq publisher: reconnect: %w", reconnErr)
		}
		// Retry once after reconnect.
		return p.ch.PublishWithContext(pubCtx, exchangeName, routingKey, false, false,
			amqp.Publishing{
				ContentType:  "application/json",
				DeliveryMode: amqp.Persistent,
				Timestamp:    time.Now(),
				Body:         body,
			})
	}
	return nil
}

// Close releases the connection.
func (p *Publisher) Close() {
	if p.conn != nil && !p.conn.IsClosed() {
		_ = p.conn.Close()
	}
}

func (p *Publisher) connect() error {
	conn, err := amqp.Dial(p.url)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	ch, err := conn.Channel()
	if err != nil {
		_ = conn.Close()
		return fmt.Errorf("channel: %w", err)
	}
	// Declare the exchange so the publisher works even when observability is not up yet.
	if err := ch.ExchangeDeclare(exchangeName, exchangeType, true, false, false, false, nil); err != nil {
		_ = conn.Close()
		return fmt.Errorf("declare exchange: %w", err)
	}
	p.conn = conn
	p.ch = ch
	return nil
}

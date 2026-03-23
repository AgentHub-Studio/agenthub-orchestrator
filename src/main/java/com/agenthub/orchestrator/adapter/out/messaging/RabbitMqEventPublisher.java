package com.agenthub.orchestrator.adapter.out.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ event publisher.
 *
 * Enabled only when `agenthub.orchestrator.events.rabbitmq-enabled=true`.
 *
 * @since 1.0.0
 */
@Component
@Primary
@ConditionalOnProperty(
    name = "agenthub.orchestrator.events.rabbitmq-enabled",
    havingValue = "true"
)
public class RabbitMqEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public RabbitMqEventPublisher(
        RabbitTemplate rabbitTemplate,
        @Value("${agenthub.orchestrator.events.exchange:agenthub.orchestrator.events}") String exchange
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    @Override
    public void publish(String eventType, Object payload) {
        rabbitTemplate.convertAndSend(exchange, eventType, payload);
    }
}

package com.github.dimitryivaniuta.gateway.dispute.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes dispute domain events to Kafka.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DisputeEventPublisher {

  public static final String TOPIC = "dispute-events";

  private final KafkaTemplate<String, Object> kafkaTemplate;

  /**
   * Sends an event to Kafka. Failures are logged and rethrown so callers can decide retry behavior.
   *
   * @param key message key (externalRef recommended)
   * @param event event payload
   */
  public void publish(String key, Object event) {
    kafkaTemplate.send(TOPIC, key, event).whenComplete((r, ex) -> {
      if (ex != null) {
        log.error("Kafka publish failed: {}", ex.getMessage(), ex);
      } else {
        log.debug("Kafka published to {} key={}", TOPIC, key);
      }
    });
  }
}

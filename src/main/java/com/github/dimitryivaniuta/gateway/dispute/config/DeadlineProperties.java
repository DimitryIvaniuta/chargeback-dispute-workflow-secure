package com.github.dimitryivaniuta.gateway.dispute.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SLA / deadline configuration.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.deadlines")
public class DeadlineProperties {

  /**
   * Deadline from OPEN state (hours).
   */
  private long openDueHours = 72;

  /**
   * Deadline when EVIDENCE_REQUESTED (hours).
   */
  private long evidenceRequestedDueHours = 168;

  /**
   * Deadline when UNDER_REVIEW (hours).
   */
  private long underReviewDueHours = 120;

  /**
   * Computes deadline duration for target state.
   *
   * @param targetState target state name
   * @return duration to due date, or null if no deadline
   */
  public Duration deadlineFor(String targetState) {
    return switch (targetState) {
      case "OPEN" -> Duration.ofHours(openDueHours);
      case "EVIDENCE_REQUESTED" -> Duration.ofHours(evidenceRequestedDueHours);
      case "UNDER_REVIEW" -> Duration.ofHours(underReviewDueHours);
      default -> null;
    };
  }
}

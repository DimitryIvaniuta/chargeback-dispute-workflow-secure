package com.github.dimitryivaniuta.gateway.dispute.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Attachment metadata configuration.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.attachments")
public class AttachmentProperties {

  /**
   * Prefix used to build storage keys for external secure storage.
   */
  private String storagePrefix = "disputes/";
}

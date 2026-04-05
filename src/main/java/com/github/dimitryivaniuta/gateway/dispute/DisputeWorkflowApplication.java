package com.github.dimitryivaniuta.gateway.dispute;

import com.github.dimitryivaniuta.gateway.dispute.config.AttachmentProperties;
import com.github.dimitryivaniuta.gateway.dispute.config.DeadlineProperties;
import com.github.dimitryivaniuta.gateway.dispute.pii.PiiProperties;
import com.github.dimitryivaniuta.gateway.dispute.storage.StorageProperties;
import com.github.dimitryivaniuta.gateway.dispute.support.ExportSigningProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Application entry point for the Chargeback / Dispute Workflow service.
 *
 * <p>This service provides secure case management with:</p>
 * <ul>
 *   <li>Case state machine + deadlines (SLA)</li>
 *   <li>Attachment metadata only; binaries stored in external secure storage</li>
 *   <li>Presigned upload/download endpoints</li>
 *   <li>RBAC + team-based access control</li>
 *   <li>Immutable audit trail for every change</li>
 *   <li>Optional encrypted PII at rest with retention purge</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties({DeadlineProperties.class, AttachmentProperties.class, StorageProperties.class, PiiProperties.class, ExportSigningProperties.class})
public class DisputeWorkflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(DisputeWorkflowApplication.class, args);
  }
}

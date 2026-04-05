package com.github.dimitryivaniuta.gateway.dispute.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Application configuration.
 */
@Configuration
@EnableConfigurationProperties({DeadlineProperties.class, AttachmentProperties.class})
public class AppConfig {}

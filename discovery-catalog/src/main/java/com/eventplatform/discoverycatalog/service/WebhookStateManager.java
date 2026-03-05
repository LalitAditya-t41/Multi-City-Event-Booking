package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.domain.WebhookConfig;
import com.eventplatform.discoverycatalog.repository.WebhookConfigRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookStateManager {

  private final WebhookConfigRepository webhookConfigRepository;
  private static final Logger log = LoggerFactory.getLogger(WebhookStateManager.class);

  public WebhookStateManager(WebhookConfigRepository webhookConfigRepository) {
    this.webhookConfigRepository = webhookConfigRepository;
  }

  @Transactional
  public void recordWebhookSuccess(Long organizationId, Instant now) {
    WebhookConfig config =
        webhookConfigRepository.findByOrganizationId(organizationId).orElse(null);
    if (config == null) {
      log.warn("Webhook config not found for org {}", organizationId);
      return;
    }
    config.recordWebhookSuccess(now);
  }

  @Transactional
  public void recordSyncSuccess(Long organizationId, Instant now) {
    WebhookConfig config =
        webhookConfigRepository.findByOrganizationId(organizationId).orElse(null);
    if (config == null) {
      log.warn("Webhook config not found for org {}", organizationId);
      return;
    }
    config.recordSyncSuccess(now);
  }

  @Transactional
  public void recordFailure(Long organizationId, Instant now, String message) {
    WebhookConfig config =
        webhookConfigRepository.findByOrganizationId(organizationId).orElse(null);
    if (config == null) {
      log.warn("Webhook config not found for org {}", organizationId);
      return;
    }
    config.recordFailure(now, message);
  }

  @Transactional
  public void recordAuthFailure(Long organizationId, Instant now, String message) {
    WebhookConfig config =
        webhookConfigRepository.findByOrganizationId(organizationId).orElse(null);
    if (config == null) {
      log.warn("Webhook config not found for org {}", organizationId);
      return;
    }
    config.recordAuthFailure(now, message);
  }
}

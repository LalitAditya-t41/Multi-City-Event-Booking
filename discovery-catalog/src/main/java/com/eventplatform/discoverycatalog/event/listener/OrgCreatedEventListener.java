package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.domain.WebhookConfig;
import com.eventplatform.discoverycatalog.repository.WebhookConfigRepository;
import com.eventplatform.shared.common.event.published.OrgCreatedEvent;
import com.eventplatform.shared.eventbrite.dto.response.EbWebhookRegistrationResult;
import com.eventplatform.shared.eventbrite.service.EbWebhookService;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrgCreatedEventListener {

  private static final Logger log = LoggerFactory.getLogger(OrgCreatedEventListener.class);

  private final EbWebhookService ebWebhookService;
  private final WebhookConfigRepository webhookConfigRepository;
  private final String endpointUrl;

  public OrgCreatedEventListener(
      EbWebhookService ebWebhookService,
      WebhookConfigRepository webhookConfigRepository,
      @Value(
              "${eventbrite.webhook.endpoint-url:http://localhost:8080/admin/v1/webhooks/eventbrite}")
          String endpointUrl) {
    this.ebWebhookService = ebWebhookService;
    this.webhookConfigRepository = webhookConfigRepository;
    this.endpointUrl = endpointUrl;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onOrgCreated(OrgCreatedEvent event) {
    List<String> actions =
        List.of(
            "event.created",
            "event.published",
            "event.updated",
            "event.unpublished",
            "order.placed",
            "order.refunded",
            "order.updated",
            "attendee.updated",
            "venue.updated");
    EbWebhookRegistrationResult result =
        ebWebhookService.registerWebhook(
            event.organizationId().toString(), endpointUrl, actions, null);
    WebhookConfig config =
        new WebhookConfig(
            event.organizationId(),
            result.webhookId(),
            result.endpointUrl(),
            result.registeredAt() == null ? Instant.now() : result.registeredAt());
    webhookConfigRepository.save(config);
    log.info("Webhook registered for org {}", event.organizationId());
  }
}

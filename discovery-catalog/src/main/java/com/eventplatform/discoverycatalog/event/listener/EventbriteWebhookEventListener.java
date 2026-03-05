package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.event.published.EventCatalogUpdatedEvent;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.service.WebhookStateManager;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.eventbrite.webhook.EbWebhookReceivedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class EventbriteWebhookEventListener {

  private static final Logger log = LoggerFactory.getLogger(EventbriteWebhookEventListener.class);

  private final EventCatalogRepository eventCatalogRepository;
  private final EventCatalogSnapshotCache snapshotCache;
  private final WebhookStateManager webhookStateManager;
  private final ApplicationEventPublisher eventPublisher;

  public EventbriteWebhookEventListener(
      EventCatalogRepository eventCatalogRepository,
      EventCatalogSnapshotCache snapshotCache,
      WebhookStateManager webhookStateManager,
      ApplicationEventPublisher eventPublisher) {
    this.eventCatalogRepository = eventCatalogRepository;
    this.snapshotCache = snapshotCache;
    this.webhookStateManager = webhookStateManager;
    this.eventPublisher = eventPublisher;
  }

  @Async
  @EventListener
  public void onWebhook(EbWebhookReceivedEvent event) {
    Long orgId = event.organizationId();
    JsonNode payload = event.payload();
    String eventId = extractEventId(payload);
    if (eventId == null) {
      log.warn("Webhook missing event_id. payload={}", payload);
      return;
    }

    try {
      eventCatalogRepository
          .findByEventbriteEventId(eventId)
          .ifPresentOrElse(
              catalogItem -> {
                webhookStateManager.recordWebhookSuccess(orgId, Instant.now());
                snapshotCache.invalidate(orgId, catalogItem.getCityId());
                eventPublisher.publishEvent(
                    new EventCatalogUpdatedEvent(
                        orgId, catalogItem.getCityId(), catalogItem.getVenueId(), Instant.now()));
              },
              () -> {
                log.warn("Webhook event not found in catalog. eventId={}", eventId);
              });
    } catch (Exception ex) {
      webhookStateManager.recordFailure(orgId, Instant.now(), ex.getMessage());
      log.error("Webhook processing failed. orgId={} eventId={}", orgId, eventId, ex);
    }
  }

  private String extractEventId(JsonNode payload) {
    if (payload == null || payload.isMissingNode()) {
      return null;
    }
    JsonNode eventIdNode = payload.get("event_id");
    if (eventIdNode != null && !eventIdNode.isNull()) {
      return eventIdNode.asText();
    }
    JsonNode apiUrlNode = payload.get("api_url");
    if (apiUrlNode != null && !apiUrlNode.isNull()) {
      String apiUrl = apiUrlNode.asText();
      int lastSlash = apiUrl.lastIndexOf('/');
      if (lastSlash >= 0 && lastSlash < apiUrl.length() - 1) {
        return apiUrl.substring(lastSlash + 1);
      }
    }
    return null;
  }
}

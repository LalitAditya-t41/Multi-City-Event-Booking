package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.domain.value.EventSyncLockKey;
import com.eventplatform.discoverycatalog.domain.value.SnapshotPayload;
import com.eventplatform.discoverycatalog.exception.CatalogSyncException;
import com.eventplatform.discoverycatalog.mapper.EventCatalogMapper;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.discoverycatalog.service.lock.EventSyncLockManager;
import com.eventplatform.discoverycatalog.service.metrics.EventCatalogMetrics;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EventCatalogRefreshService {

  private static final Logger log = LoggerFactory.getLogger(EventCatalogRefreshService.class);

  private final EventSyncLockManager lockManager;
  private final EventCatalogSyncService syncService;
  private final EventCatalogRepository eventCatalogRepository;
  private final EventCatalogMapper eventCatalogMapper;
  private final EventCatalogSnapshotCache snapshotCache;
  private final WebhookStateManager webhookStateManager;
  private final EventCatalogMetrics metrics;

  public EventCatalogRefreshService(
      EventSyncLockManager lockManager,
      EventCatalogSyncService syncService,
      EventCatalogRepository eventCatalogRepository,
      EventCatalogMapper eventCatalogMapper,
      EventCatalogSnapshotCache snapshotCache,
      WebhookStateManager webhookStateManager,
      EventCatalogMetrics metrics) {
    this.lockManager = lockManager;
    this.syncService = syncService;
    this.eventCatalogRepository = eventCatalogRepository;
    this.eventCatalogMapper = eventCatalogMapper;
    this.snapshotCache = snapshotCache;
    this.webhookStateManager = webhookStateManager;
    this.metrics = metrics;
  }

  @Async
  public void refreshAsync(Long organizationId, Long cityId, Long venueId) {
    EventSyncLockKey lockKey = new EventSyncLockKey(organizationId, cityId, venueId);
    if (!lockManager.acquire(lockKey)) {
      log.warn("Lock timeout for org={} city={} venue={}", organizationId, cityId, venueId);
      return;
    }
    try {
      syncService.sync(organizationId, cityId, venueId);
      refreshSnapshot(organizationId, cityId);
      webhookStateManager.recordSyncSuccess(organizationId, Instant.now());
    } catch (EbAuthException ex) {
      metrics.incrementAuthFailure(organizationId);
      webhookStateManager.recordAuthFailure(organizationId, Instant.now(), ex.getMessage());
      log.error("Eventbrite auth failure. orgId={}", organizationId, ex);
    } catch (EbIntegrationException ex) {
      webhookStateManager.recordFailure(organizationId, Instant.now(), ex.getMessage());
      log.error(
          "Eventbrite integration failure. orgId={} cityId={} venueId={}",
          organizationId,
          cityId,
          venueId,
          ex);
    } catch (Exception ex) {
      webhookStateManager.recordFailure(organizationId, Instant.now(), ex.getMessage());
      log.error(
          "Catalog refresh failed. orgId={} cityId={} venueId={}",
          organizationId,
          cityId,
          venueId,
          ex);
      throw new CatalogSyncException("Catalog refresh failed", ex);
    } finally {
      lockManager.release(lockKey);
    }
  }

  private void refreshSnapshot(Long organizationId, Long cityId) {
    try {
      List<com.eventplatform.discoverycatalog.api.dto.response.EventCatalogItemResponse> events =
          eventCatalogRepository
              .findByOrganizationIdAndCityIdAndDeletedAtIsNull(organizationId, cityId)
              .stream()
              .map(eventCatalogMapper::toResponse)
              .toList();
      snapshotCache.putSnapshot(organizationId, cityId, new SnapshotPayload(events, Instant.now()));
    } catch (Exception ex) {
      log.warn("Cache write failed. orgId={} cityId={}", organizationId, cityId, ex);
    }
  }
}

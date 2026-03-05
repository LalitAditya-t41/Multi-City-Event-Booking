package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.common.event.published.ShowSlotCancelledEvent;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlotCancelledListener {

  private final EventCatalogRepository eventCatalogRepository;
  private final EventCatalogSnapshotCache snapshotCache;

  public SlotCancelledListener(
      EventCatalogRepository eventCatalogRepository, EventCatalogSnapshotCache snapshotCache) {
    this.eventCatalogRepository = eventCatalogRepository;
    this.snapshotCache = snapshotCache;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSlotCancelled(ShowSlotCancelledEvent event) {
    eventCatalogRepository
        .findByEventbriteEventId(event.ebEventId())
        .ifPresent(
            item -> {
              item.markCancelled(Instant.now());
              eventCatalogRepository.save(item);
            });
    snapshotCache.invalidate(event.orgId(), event.cityId());
  }
}

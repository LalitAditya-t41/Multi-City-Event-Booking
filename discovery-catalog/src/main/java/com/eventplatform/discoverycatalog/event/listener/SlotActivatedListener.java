package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.service.EventCatalogSyncService;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.common.event.published.ShowSlotActivatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlotActivatedListener {

    private final EventCatalogSyncService eventCatalogSyncService;
    private final EventCatalogSnapshotCache snapshotCache;

    public SlotActivatedListener(
        EventCatalogSyncService eventCatalogSyncService,
        EventCatalogSnapshotCache snapshotCache
    ) {
        this.eventCatalogSyncService = eventCatalogSyncService;
        this.snapshotCache = snapshotCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlotActivated(ShowSlotActivatedEvent event) {
        eventCatalogSyncService.sync(event.orgId(), event.cityId(), event.venueId());
        snapshotCache.invalidate(event.orgId(), event.cityId());
    }
}

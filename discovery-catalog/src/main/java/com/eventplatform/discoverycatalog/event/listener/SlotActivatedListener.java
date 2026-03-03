package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.service.EventCatalogSyncService;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.common.event.published.ShowSlotActivatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    @EventListener
    public void onSlotActivated(ShowSlotActivatedEvent event) {
        eventCatalogSyncService.sync(event.orgId(), event.cityId(), event.venueId());
        snapshotCache.invalidate(event.orgId(), event.cityId());
    }
}

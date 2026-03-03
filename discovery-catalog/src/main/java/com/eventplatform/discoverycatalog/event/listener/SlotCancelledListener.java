package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.shared.common.event.published.ShowSlotCancelledEvent;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SlotCancelledListener {

    private final EventCatalogRepository eventCatalogRepository;
    private final EventCatalogSnapshotCache snapshotCache;

    public SlotCancelledListener(
        EventCatalogRepository eventCatalogRepository,
        EventCatalogSnapshotCache snapshotCache
    ) {
        this.eventCatalogRepository = eventCatalogRepository;
        this.snapshotCache = snapshotCache;
    }

    @EventListener
    public void onSlotCancelled(ShowSlotCancelledEvent event) {
        eventCatalogRepository.findByEventbriteEventId(event.ebEventId()).ifPresent(item -> {
            item.markCancelled(Instant.now());
            eventCatalogRepository.save(item);
        });
        snapshotCache.invalidate(event.orgId(), event.cityId());
    }
}

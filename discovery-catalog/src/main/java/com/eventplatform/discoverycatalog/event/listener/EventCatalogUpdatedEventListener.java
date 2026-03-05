package com.eventplatform.discoverycatalog.event.listener;

import com.eventplatform.discoverycatalog.event.published.EventCatalogUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class EventCatalogUpdatedEventListener {

  private static final Logger log = LoggerFactory.getLogger(EventCatalogUpdatedEventListener.class);

  @EventListener
  public void onCatalogUpdated(EventCatalogUpdatedEvent event) {
    log.info(
        "Catalog updated. orgId={} cityId={} venueId={}",
        event.organizationId(),
        event.cityId(),
        event.venueId());
  }
}

package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.enums.EventSource;
import com.eventplatform.discoverycatalog.domain.enums.EventState;
import com.eventplatform.discoverycatalog.exception.CatalogSyncException;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.shared.eventbrite.dto.response.EbEventDto;
import com.eventplatform.shared.eventbrite.exception.EbAuthException;
import com.eventplatform.shared.eventbrite.exception.EbIntegrationException;
import com.eventplatform.shared.eventbrite.service.EbEventSyncService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventCatalogSyncService {

  private static final Logger log = LoggerFactory.getLogger(EventCatalogSyncService.class);

  private final EbEventSyncService ebEventSyncService;
  private final EventCatalogRepository eventCatalogRepository;
  private final VenueRepository venueRepository;

  public EventCatalogSyncService(
      EbEventSyncService ebEventSyncService,
      EventCatalogRepository eventCatalogRepository,
      VenueRepository venueRepository) {
    this.ebEventSyncService = ebEventSyncService;
    this.eventCatalogRepository = eventCatalogRepository;
    this.venueRepository = venueRepository;
  }

  @Transactional
  public void sync(Long organizationId, Long cityId, Long venueId) {
    List<EbEventDto> ebEvents = fetchEvents(organizationId, venueId);
    Map<String, EbEventDto> ebEventById = new HashMap<>();
    for (EbEventDto ebEvent : ebEvents) {
      ebEventById.put(ebEvent.id(), ebEvent);
    }

    for (EbEventDto ebEvent : ebEvents) {
      upsertEvent(organizationId, cityId, ebEvent);
    }

    softDeleteMissing(organizationId, cityId, venueId, ebEventById.keySet());
  }

  private List<EbEventDto> fetchEvents(Long organizationId, Long venueId) {
    try {
      if (venueId != null) {
        String eventbriteVenueId =
            venueRepository.findById(venueId).map(Venue::getEventbriteVenueId).orElse(null);
        if (eventbriteVenueId == null) {
          throw new CatalogSyncException("Venue missing eventbrite_venue_id: " + venueId);
        }
        return ebEventSyncService.listEventsByVenue(eventbriteVenueId);
      }
      return ebEventSyncService.listEventsByOrganization(organizationId.toString());
    } catch (EbAuthException ex) {
      throw ex;
    } catch (EbIntegrationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new CatalogSyncException("Failed to sync events from Eventbrite", ex);
    }
  }

  private void upsertEvent(Long organizationId, Long cityId, EbEventDto ebEvent) {
    if (ebEvent.venueId() == null) {
      log.warn("Skipping Eventbrite event without venue id. eventId={}", ebEvent.id());
      return;
    }
    var venueOpt = venueRepository.findByEventbriteVenueId(ebEvent.venueId());
    if (venueOpt.isEmpty()) {
      log.warn("Skipping Eventbrite event with unknown venue. venueId={}", ebEvent.venueId());
      return;
    }
    Long resolvedCityId = venueOpt.get().getCityId();
    if (!resolvedCityId.equals(cityId)) {
      log.debug(
          "Event city mismatch. eventId={} expectedCityId={} actualCityId={}",
          ebEvent.id(),
          cityId,
          resolvedCityId);
    }
    EventCatalogItem incoming =
        new EventCatalogItem(
            organizationId,
            resolvedCityId,
            ebEvent.id(),
            ebEvent.name(),
            ebEvent.startTime(),
            ebEvent.endTime());
    incoming.applyEventbriteDetails(
        venueOpt.get().getId(),
        ebEvent.description(),
        ebEvent.url(),
        mapState(ebEvent.state()),
        EventSource.EVENTBRITE_EXTERNAL,
        ebEvent.currency(),
        ebEvent.changedAt());

    eventCatalogRepository
        .findByEventbriteEventId(ebEvent.id())
        .ifPresentOrElse(
            existing -> {
              if (shouldUpdate(existing, ebEvent.changedAt())) {
                existing.updateFrom(incoming);
              }
            },
            () -> eventCatalogRepository.save(incoming));
  }

  private boolean shouldUpdate(EventCatalogItem existing, Instant incomingChangedAt) {
    if (incomingChangedAt == null) {
      return true;
    }
    Instant currentChangedAt = existing.getEventbriteChangedAt();
    return currentChangedAt == null || incomingChangedAt.isAfter(currentChangedAt);
  }

  private EventState mapState(String state) {
    if (state == null) {
      return EventState.PUBLISHED;
    }
    return switch (state.toUpperCase()) {
      case "DRAFT" -> EventState.DRAFT;
      case "CANCELLED" -> EventState.CANCELLED;
      default -> EventState.PUBLISHED;
    };
  }

  private void softDeleteMissing(
      Long organizationId, Long cityId, Long venueId, Set<String> eventbriteIds) {
    List<EventCatalogItem> existing;
    if (venueId == null) {
      existing =
          eventCatalogRepository.findByOrganizationIdAndCityIdAndDeletedAtIsNull(
              organizationId, cityId);
    } else {
      existing =
          eventCatalogRepository.findByOrganizationIdAndCityIdAndVenueIdAndDeletedAtIsNull(
              organizationId, cityId, venueId);
    }
    Instant now = Instant.now();
    List<EventCatalogItem> toDelete =
        existing.stream()
            .filter(item -> !eventbriteIds.contains(item.getEventbriteEventId()))
            .toList();
    toDelete.forEach(item -> item.softDelete(now));
    if (!toDelete.isEmpty()) {
      log.info(
          "Soft-deleted {} missing events for orgId={} cityId={} venueId={}",
          toDelete.size(),
          organizationId,
          cityId,
          venueId);
    }
  }
}

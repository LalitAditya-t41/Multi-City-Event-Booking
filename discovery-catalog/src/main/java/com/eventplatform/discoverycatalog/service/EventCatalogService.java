package com.eventplatform.discoverycatalog.service;

import com.eventplatform.discoverycatalog.api.dto.request.EventCatalogSearchRequest;
import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogItemResponse;
import com.eventplatform.discoverycatalog.api.dto.response.EventCatalogSearchResponse;
import com.eventplatform.discoverycatalog.api.dto.response.PaginationInfo;
import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import com.eventplatform.discoverycatalog.domain.enums.CatalogSource;
import com.eventplatform.discoverycatalog.domain.value.SnapshotPayload;
import com.eventplatform.discoverycatalog.exception.CatalogNotFoundException;
import com.eventplatform.discoverycatalog.exception.InvalidCatalogSearchException;
import com.eventplatform.discoverycatalog.mapper.EventCatalogMapper;
import com.eventplatform.discoverycatalog.repository.CityRepository;
import com.eventplatform.discoverycatalog.repository.EventCatalogRepository;
import com.eventplatform.discoverycatalog.repository.VenueRepository;
import com.eventplatform.discoverycatalog.repository.WebhookConfigRepository;
import com.eventplatform.discoverycatalog.service.cache.EventCatalogSnapshotCache;
import com.eventplatform.discoverycatalog.service.metrics.EventCatalogMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class EventCatalogService {

  private static final Logger log = LoggerFactory.getLogger(EventCatalogService.class);
  private static final Duration WEBHOOK_STALE_THRESHOLD = Duration.ofHours(1);

  private final EventCatalogRepository eventCatalogRepository;
  private final CityRepository cityRepository;
  private final VenueRepository venueRepository;
  private final WebhookConfigRepository webhookConfigRepository;
  private final EventCatalogSnapshotCache snapshotCache;
  private final EventCatalogMapper eventCatalogMapper;
  private final EventCatalogRefreshService refreshService;
  private final EventCatalogMetrics metrics;

  public EventCatalogService(
      EventCatalogRepository eventCatalogRepository,
      CityRepository cityRepository,
      VenueRepository venueRepository,
      WebhookConfigRepository webhookConfigRepository,
      EventCatalogSnapshotCache snapshotCache,
      EventCatalogMapper eventCatalogMapper,
      EventCatalogRefreshService refreshService,
      EventCatalogMetrics metrics) {
    this.eventCatalogRepository = eventCatalogRepository;
    this.cityRepository = cityRepository;
    this.venueRepository = venueRepository;
    this.webhookConfigRepository = webhookConfigRepository;
    this.snapshotCache = snapshotCache;
    this.eventCatalogMapper = eventCatalogMapper;
    this.refreshService = refreshService;
    this.metrics = metrics;
  }

  public EventCatalogSearchResponse search(Long organizationId, EventCatalogSearchRequest request) {
    validateSearch(request);
    ensureCityExists(request.cityId());
    if (request.venueId() != null && !venueRepository.existsById(request.venueId())) {
      throw new CatalogNotFoundException("Venue not found: " + request.venueId());
    }

    boolean triggeredRefresh = triggerRefreshIfStale(organizationId, request.cityId());
    CatalogSource source;
    List<EventCatalogItemResponse> events;
    Instant snapshotTimestamp = null;

    Optional<SnapshotPayload> snapshotOpt =
        snapshotCache.getSnapshot(organizationId, request.cityId());
    if (snapshotOpt.isPresent()) {
      metrics.incrementCacheHit(organizationId);
      source = CatalogSource.CACHE;
      snapshotTimestamp = snapshotOpt.get().snapshotTimestamp();
      events = applyFilters(snapshotOpt.get().events(), request);
    } else {
      metrics.incrementCacheMiss(organizationId);
      source = CatalogSource.DB;
      Page<EventCatalogItem> page =
          eventCatalogRepository.findAll(
              buildSpecification(organizationId, request),
              PageRequest.of(request.page(), request.size()));
      events = page.map(eventCatalogMapper::toResponse).getContent();
      refreshSnapshotCacheFromDb(organizationId, request.cityId());
      PaginationInfo pagination =
          new PaginationInfo(
              request.page(), request.size(), page.getTotalElements(), page.getTotalPages());
      return new EventCatalogSearchResponse(
          events, triggeredRefresh, snapshotTimestamp, source, pagination);
    }

    PaginationInfo pagination = paginateInfo(events.size(), request.page(), request.size());
    List<EventCatalogItemResponse> pagedEvents = paginate(events, request.page(), request.size());
    return new EventCatalogSearchResponse(
        pagedEvents, triggeredRefresh, snapshotTimestamp, source, pagination);
  }

  private void validateSearch(EventCatalogSearchRequest request) {
    if (request.cityId() == null) {
      throw new InvalidCatalogSearchException("cityId is required");
    }
    if (request.size() > 100) {
      throw new InvalidCatalogSearchException("size must be <= 100");
    }
  }

  private void ensureCityExists(Long cityId) {
    if (!cityRepository.existsById(cityId)) {
      throw new CatalogNotFoundException("City not found: " + cityId);
    }
  }

  private boolean triggerRefreshIfStale(Long organizationId, Long cityId) {
    Instant now = Instant.now();
    return webhookConfigRepository
        .findByOrganizationId(organizationId)
        .map(
            config -> {
              Instant lastWebhookAt = config.getLastWebhookAt();
              if (lastWebhookAt == null
                  || lastWebhookAt.isBefore(now.minus(WEBHOOK_STALE_THRESHOLD))) {
                metrics.incrementReadPathRefresh(organizationId);
                log.info("Read-path refresh triggered. orgId={} cityId={}", organizationId, cityId);
                refreshService.refreshAsync(organizationId, cityId, null);
                return true;
              }
              return false;
            })
        .orElse(false);
  }

  private List<EventCatalogItemResponse> applyFilters(
      List<EventCatalogItemResponse> events, EventCatalogSearchRequest request) {
    Predicate<EventCatalogItemResponse> predicate = item -> true;
    if (request.venueId() != null) {
      predicate = predicate.and(item -> request.venueId().equals(item.venueId()));
    }
    if (request.state() != null) {
      predicate = predicate.and(item -> request.state() == item.state());
    }
    if (request.q() != null && !request.q().isBlank()) {
      String qLower = request.q().toLowerCase();
      predicate =
          predicate.and(item -> item.name() != null && item.name().toLowerCase().contains(qLower));
    }
    if (request.startAfter() != null) {
      predicate =
          predicate.and(
              item -> item.startTime() != null && item.startTime().isAfter(request.startAfter()));
    }
    if (request.startBefore() != null) {
      predicate =
          predicate.and(
              item -> item.startTime() != null && item.startTime().isBefore(request.startBefore()));
    }
    return events.stream().filter(predicate).toList();
  }

  private Specification<EventCatalogItem> buildSpecification(
      Long organizationId, EventCatalogSearchRequest request) {
    return (root, query, cb) -> {
      var predicates = cb.conjunction();
      predicates = cb.and(predicates, cb.equal(root.get("organizationId"), organizationId));
      predicates = cb.and(predicates, cb.equal(root.get("cityId"), request.cityId()));
      predicates = cb.and(predicates, cb.isNull(root.get("deletedAt")));
      if (request.venueId() != null) {
        predicates = cb.and(predicates, cb.equal(root.get("venueId"), request.venueId()));
      }
      if (request.state() != null) {
        predicates = cb.and(predicates, cb.equal(root.get("state"), request.state()));
      }
      if (request.q() != null && !request.q().isBlank()) {
        predicates =
            cb.and(
                predicates,
                cb.like(cb.lower(root.get("name")), "%" + request.q().toLowerCase() + "%"));
      }
      if (request.startAfter() != null) {
        predicates =
            cb.and(predicates, cb.greaterThan(root.get("startTime"), request.startAfter()));
      }
      if (request.startBefore() != null) {
        predicates = cb.and(predicates, cb.lessThan(root.get("startTime"), request.startBefore()));
      }
      return predicates;
    };
  }

  private PaginationInfo paginateInfo(long totalElements, int page, int size) {
    int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / (double) size);
    return new PaginationInfo(page, size, totalElements, totalPages);
  }

  private List<EventCatalogItemResponse> paginate(
      List<EventCatalogItemResponse> events, int page, int size) {
    if (size == 0) {
      return events;
    }
    int fromIndex = Math.min(page * size, events.size());
    int toIndex = Math.min(fromIndex + size, events.size());
    return events.subList(fromIndex, toIndex);
  }

  private void refreshSnapshotCacheFromDb(Long organizationId, Long cityId) {
    try {
      List<EventCatalogItemResponse> allEvents =
          eventCatalogRepository
              .findByOrganizationIdAndCityIdAndDeletedAtIsNull(organizationId, cityId)
              .stream()
              .map(eventCatalogMapper::toResponse)
              .toList();
      snapshotCache.putSnapshot(
          organizationId, cityId, new SnapshotPayload(allEvents, Instant.now()));
    } catch (Exception ex) {
      log.warn(
          "Failed to rebuild snapshot cache from DB. orgId={} cityId={}",
          organizationId,
          cityId,
          ex);
    }
  }
}

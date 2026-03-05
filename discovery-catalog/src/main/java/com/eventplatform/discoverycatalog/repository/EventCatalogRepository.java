package com.eventplatform.discoverycatalog.repository;

import com.eventplatform.discoverycatalog.domain.EventCatalogItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventCatalogRepository
    extends JpaRepository<EventCatalogItem, Long>, JpaSpecificationExecutor<EventCatalogItem> {
  Optional<EventCatalogItem> findByEventbriteEventId(String eventbriteEventId);

  List<EventCatalogItem> findByOrganizationIdAndCityIdAndDeletedAtIsNull(
      Long organizationId, Long cityId);

  List<EventCatalogItem> findByOrganizationIdAndCityIdAndVenueIdAndDeletedAtIsNull(
      Long organizationId, Long cityId, Long venueId);

  List<EventCatalogItem>
      findByOrganizationIdAndCityIdAndDeletedAtIsNullAndEventbriteChangedAtBefore(
          Long organizationId, Long cityId, Instant changedBefore);
}

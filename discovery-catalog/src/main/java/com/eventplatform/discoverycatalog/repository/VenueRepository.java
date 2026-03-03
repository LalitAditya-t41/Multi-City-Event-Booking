package com.eventplatform.discoverycatalog.repository;

import com.eventplatform.discoverycatalog.domain.Venue;
import com.eventplatform.discoverycatalog.domain.enums.VenueSyncStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    Page<Venue> findByOrganizationIdAndCityId(Long organizationId, Long cityId, Pageable pageable);

    Optional<Venue> findByEventbriteVenueId(String eventbriteVenueId);

    List<Venue> findByOrganizationId(Long organizationId);

    List<Venue> findByCityId(Long cityId);

    Page<Venue> findBySyncStatus(VenueSyncStatus syncStatus, Pageable pageable);
}

package com.eventplatform.discoverycatalog.repository;

import com.eventplatform.discoverycatalog.domain.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRepository extends JpaRepository<Venue, Long> {
    Page<Venue> findByOrganizationIdAndCityId(Long organizationId, Long cityId, Pageable pageable);

    java.util.Optional<Venue> findByEventbriteVenueId(String eventbriteVenueId);
}

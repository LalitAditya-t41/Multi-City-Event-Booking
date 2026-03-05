package com.eventplatform.discoverycatalog.repository;

import com.eventplatform.discoverycatalog.domain.VenueSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueSeatRepository extends JpaRepository<VenueSeat, Long> {
  List<VenueSeat> findByVenueId(Long venueId);

  List<VenueSeat> findByVenueIdAndTierName(Long venueId, String tierName);

  long countByVenueId(Long venueId);
}

package com.eventplatform.scheduling.repository;

import com.eventplatform.scheduling.domain.ShowSlotPricingTier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowSlotPricingTierRepository extends JpaRepository<ShowSlotPricingTier, Long> {
    List<ShowSlotPricingTier> findBySlotId(Long slotId);
}

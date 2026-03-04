package com.eventplatform.bookinginventory.repository;

import com.eventplatform.bookinginventory.domain.GaInventoryClaim;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GaInventoryClaimRepository extends JpaRepository<GaInventoryClaim, Long> {

    Optional<GaInventoryClaim> findByShowSlotIdAndPricingTierIdAndUserIdAndCartId(Long showSlotId, Long pricingTierId, Long userId, Long cartId);

    List<GaInventoryClaim> findByLockStateAndLockedUntilBefore(SeatLockState lockState, Instant now);

    long countByShowSlotIdAndPricingTierIdAndLockState(Long showSlotId, Long pricingTierId, SeatLockState lockState);
}

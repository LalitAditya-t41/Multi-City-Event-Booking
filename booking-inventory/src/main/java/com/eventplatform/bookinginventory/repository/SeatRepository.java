package com.eventplatform.bookinginventory.repository;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    @Query("""
        select s from Seat s
        where s.showSlotId = :slotId
          and (
            s.lockState = com.eventplatform.bookinginventory.domain.enums.SeatLockState.AVAILABLE
            or (s.lockState = com.eventplatform.bookinginventory.domain.enums.SeatLockState.SOFT_LOCKED and s.lockedUntil < :now)
          )
        """)
    List<Seat> findAvailableForSlot(@Param("slotId") Long slotId, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id = :seatId")
    Optional<Seat> findByIdWithLock(@Param("seatId") Long seatId);

    List<Seat> findByShowSlotIdAndLockStateIn(Long showSlotId, Collection<SeatLockState> states);

    List<Seat> findByLockStateAndLockedUntilBefore(SeatLockState lockState, Instant now);

    @Query("select s from Seat s where s.showSlotId = :slotId and s.section = :section and s.pricingTierId = :tierId and s.lockState = com.eventplatform.bookinginventory.domain.enums.SeatLockState.AVAILABLE")
    List<Seat> findAlternativesSameSection(@Param("slotId") Long slotId, @Param("section") String section, @Param("tierId") Long tierId);

    @Query("select s from Seat s where s.showSlotId = :slotId and s.section <> :section and s.pricingTierId = :tierId and s.lockState = com.eventplatform.bookinginventory.domain.enums.SeatLockState.AVAILABLE")
    List<Seat> findAlternativesAdjacentSection(@Param("slotId") Long slotId, @Param("section") String section, @Param("tierId") Long tierId);

    boolean existsByShowSlotId(Long showSlotId);
}

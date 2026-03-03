package com.eventplatform.scheduling.repository;

import com.eventplatform.scheduling.domain.ShowSlot;
import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowSlotRepository extends JpaRepository<ShowSlot, Long>, JpaSpecificationExecutor<ShowSlot> {

    @Query("select s from ShowSlot s where s.venueId = :venueId and s.status <> 'CANCELLED' and s.startTime < :endTime and s.endTime > :startTime")
    List<ShowSlot> findConflicts(
        @Param("venueId") Long venueId,
        @Param("startTime") ZonedDateTime startTime,
        @Param("endTime") ZonedDateTime endTime
    );

    Optional<ShowSlot> findFirstByVenueIdAndEndTimeLessThanEqualOrderByEndTimeDesc(Long venueId, ZonedDateTime startTime);

    Optional<ShowSlot> findFirstByVenueIdAndStartTimeGreaterThanEqualOrderByStartTimeAsc(Long venueId, ZonedDateTime endTime);

    Page<ShowSlot> findByOrganizationIdAndStatus(Long organizationId, ShowSlotStatus status, Pageable pageable);

    Page<ShowSlot> findByOrganizationIdAndStatusAndSyncAttemptCountGreaterThan(Long organizationId, ShowSlotStatus status, int attempts, Pageable pageable);

    Page<ShowSlot> findByOrganizationIdAndStatusAndLastSyncErrorIsNotNull(Long organizationId, ShowSlotStatus status, Pageable pageable);
}

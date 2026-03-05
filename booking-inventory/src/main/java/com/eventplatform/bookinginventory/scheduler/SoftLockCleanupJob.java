package com.eventplatform.bookinginventory.scheduler;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.SeatLockAuditLog;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.bookinginventory.repository.SeatLockAuditLogRepository;
import com.eventplatform.bookinginventory.repository.SeatRepository;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class SoftLockCleanupJob {

  private final SeatRepository seatRepository;
  private final SeatLockAuditLogRepository auditRepository;
  private final SeatLockRedisService seatLockRedisService;

  public SoftLockCleanupJob(
      SeatRepository seatRepository,
      SeatLockAuditLogRepository auditRepository,
      SeatLockRedisService seatLockRedisService) {
    this.seatRepository = seatRepository;
    this.auditRepository = auditRepository;
    this.seatLockRedisService = seatLockRedisService;
  }

  @Scheduled(fixedDelay = 60000)
  @Transactional
  public void cleanupExpiredSoftLocks() {
    for (Seat seat :
        seatRepository.findByLockStateAndLockedUntilBefore(
            SeatLockState.SOFT_LOCKED, Instant.now())) {
      Long lockedUser = seat.getLockedByUserId();
      SeatLockState from = seat.getLockState();
      seat.release(LockReleaseReason.TTL_EXPIRED);
      seatRepository.save(seat);
      if (lockedUser != null) {
        seatLockRedisService.release(seat.getId(), lockedUser);
      }
      auditRepository.save(
          new SeatLockAuditLog(
              seat.getId(),
              null,
              seat.getShowSlotId(),
              lockedUser,
              from,
              SeatLockState.AVAILABLE,
              SeatLockEvent.RELEASE,
              LockReleaseReason.TTL_EXPIRED.name(),
              null));
    }
  }
}

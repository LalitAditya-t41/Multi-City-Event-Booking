package com.eventplatform.bookinginventory.statemachine.guard;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityGuard {

    private final SeatLockRedisService seatLockRedisService;

    public AvailabilityGuard(SeatLockRedisService seatLockRedisService) {
        this.seatLockRedisService = seatLockRedisService;
    }

    public boolean canSelect(Seat seat, Long userId, Duration ttl) {
        if (!seat.isSelectable(Instant.now())) {
            return false;
        }
        SeatLockRedisService.AcquireResult result = seatLockRedisService.acquire(seat.getId(), userId, ttl);
        return result == SeatLockRedisService.AcquireResult.ACQUIRED || result == SeatLockRedisService.AcquireResult.ALREADY_YOURS;
    }
}

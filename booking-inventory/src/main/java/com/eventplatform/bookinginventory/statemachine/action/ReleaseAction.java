package com.eventplatform.bookinginventory.statemachine.action;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.bookinginventory.statemachine.SeatLockStateMachineService.SeatActionContext;
import org.springframework.stereotype.Component;

@Component
public class ReleaseAction {

    private final SeatLockRedisService seatLockRedisService;

    public ReleaseAction(SeatLockRedisService seatLockRedisService) {
        this.seatLockRedisService = seatLockRedisService;
    }

    public void apply(Seat seat, SeatActionContext context) {
        LockReleaseReason reason = context.reason() == null
            ? LockReleaseReason.USER_REMOVED
            : LockReleaseReason.valueOf(context.reason());
        seat.release(reason);
        seatLockRedisService.release(seat.getId(), context.userId());
    }
}

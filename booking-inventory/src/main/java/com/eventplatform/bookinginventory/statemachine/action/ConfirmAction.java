package com.eventplatform.bookinginventory.statemachine.action;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.bookinginventory.statemachine.SeatLockStateMachineService.SeatActionContext;
import org.springframework.stereotype.Component;

@Component
public class ConfirmAction {

  private final SeatLockRedisService seatLockRedisService;

  public ConfirmAction(SeatLockRedisService seatLockRedisService) {
    this.seatLockRedisService = seatLockRedisService;
  }

  public void apply(Seat seat, SeatActionContext context) {
    seat.confirm(context.bookingRef());
    seatLockRedisService.release(seat.getId(), context.userId());
  }
}

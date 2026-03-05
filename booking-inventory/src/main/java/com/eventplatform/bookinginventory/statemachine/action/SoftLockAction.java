package com.eventplatform.bookinginventory.statemachine.action;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.bookinginventory.statemachine.SeatLockStateMachineService.SeatActionContext;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class SoftLockAction {

  private final SeatLockRedisService seatLockRedisService;

  public SoftLockAction(SeatLockRedisService seatLockRedisService) {
    this.seatLockRedisService = seatLockRedisService;
  }

  public void apply(Seat seat, SeatActionContext context) {
    seat.softLock(context.userId(), Duration.ofMinutes(5));
    seatLockRedisService.acquire(seat.getId(), context.userId(), Duration.ofMinutes(5));
  }
}

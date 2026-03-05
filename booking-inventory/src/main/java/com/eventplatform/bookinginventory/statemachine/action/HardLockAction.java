package com.eventplatform.bookinginventory.statemachine.action;

import com.eventplatform.bookinginventory.domain.Seat;
import com.eventplatform.bookinginventory.exception.HardLockException;
import com.eventplatform.bookinginventory.service.redis.SeatLockRedisService;
import com.eventplatform.bookinginventory.statemachine.SeatLockStateMachineService.SeatActionContext;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HardLockAction {

  private final SeatLockRedisService seatLockRedisService;

  public HardLockAction(SeatLockRedisService seatLockRedisService) {
    this.seatLockRedisService = seatLockRedisService;
  }

  public void apply(Seat seat, SeatActionContext context) {
    if (!seatLockRedisService.extend(seat.getId(), context.userId(), Duration.ofMinutes(30))) {
      throw new HardLockException("Redis ownership lost before hard lock");
    }
    seat.hardLock(Duration.ofMinutes(30));
  }
}

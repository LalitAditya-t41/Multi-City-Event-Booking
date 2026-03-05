package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class SeatAlreadyLockedException extends BaseException {
  public SeatAlreadyLockedException(Long seatId) {
    super("Seat already locked: " + seatId, "SEAT_UNAVAILABLE", HttpStatus.CONFLICT);
  }
}

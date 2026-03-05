package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class SeatUnavailableException extends BaseException {
  public SeatUnavailableException(Long seatId, Object alternatives) {
    super(
        "Seat unavailable",
        "SEAT_UNAVAILABLE",
        HttpStatus.CONFLICT,
        Map.of("requestedSeatId", seatId, "alternatives", alternatives));
  }
}

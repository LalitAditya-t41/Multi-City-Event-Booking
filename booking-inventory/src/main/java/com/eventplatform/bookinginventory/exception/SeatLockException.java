package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class SeatLockException extends BaseException {
  public SeatLockException(String message) {
    super(message, "SEAT_LOCK_DB_FAILURE", HttpStatus.INTERNAL_SERVER_ERROR);
  }
}

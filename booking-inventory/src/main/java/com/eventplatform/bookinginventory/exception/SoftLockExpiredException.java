package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

public class SoftLockExpiredException extends BaseException {
  public SoftLockExpiredException(Set<Long> seatIds) {
    super(
        "Soft lock expired",
        "SOFT_LOCK_EXPIRED",
        HttpStatus.GONE,
        Map.of("expiredSeatIds", seatIds));
  }
}

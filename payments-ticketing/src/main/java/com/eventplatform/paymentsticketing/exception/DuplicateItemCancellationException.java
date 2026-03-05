package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class DuplicateItemCancellationException extends BaseException {

  public DuplicateItemCancellationException(Long bookingItemId) {
    super(
        "Cancellation already requested for booking item: " + bookingItemId,
        "DUPLICATE_ITEM_CANCELLATION",
        HttpStatus.CONFLICT,
        Map.of("bookingItemId", bookingItemId));
  }
}

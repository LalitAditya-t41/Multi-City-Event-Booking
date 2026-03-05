package com.eventplatform.paymentsticketing.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class CancellationNotAllowedException extends BaseException {

  public CancellationNotAllowedException(String reason) {
    super(reason, "CANCELLATION_NOT_ALLOWED", HttpStatus.CONFLICT, Map.of("reason", reason));
  }

  public CancellationNotAllowedException(String reason, Object details) {
    super(reason, "CANCELLATION_NOT_ALLOWED", HttpStatus.CONFLICT, details);
  }
}

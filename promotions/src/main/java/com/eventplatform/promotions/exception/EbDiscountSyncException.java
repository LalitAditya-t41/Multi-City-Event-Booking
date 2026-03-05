package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class EbDiscountSyncException extends BaseException {
  public EbDiscountSyncException(String message) {
    super(message, "EB_DISCOUNT_SYNC_FAILED", HttpStatus.BAD_GATEWAY);
  }
}

package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CouponUsageLimitReachedException extends BaseException {
  public CouponUsageLimitReachedException() {
    super("Coupon usage limit reached", "COUPON_USAGE_LIMIT_REACHED", HttpStatus.CONFLICT);
  }
}

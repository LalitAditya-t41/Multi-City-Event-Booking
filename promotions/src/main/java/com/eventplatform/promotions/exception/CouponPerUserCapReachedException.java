package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CouponPerUserCapReachedException extends BaseException {
  public CouponPerUserCapReachedException() {
    super("Coupon per-user cap reached", "COUPON_PER_USER_CAP_REACHED", HttpStatus.CONFLICT);
  }
}

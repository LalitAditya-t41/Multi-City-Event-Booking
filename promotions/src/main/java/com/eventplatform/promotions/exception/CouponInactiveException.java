package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CouponInactiveException extends BaseException {
  public CouponInactiveException(String code) {
    super("Coupon inactive: " + code, "COUPON_INACTIVE", HttpStatus.GONE);
  }
}

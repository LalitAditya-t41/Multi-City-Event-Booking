package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BusinessRuleException;

public class CouponExpiredException extends BusinessRuleException {
  public CouponExpiredException(String code) {
    super("Coupon expired: " + code, "COUPON_EXPIRED");
  }
}

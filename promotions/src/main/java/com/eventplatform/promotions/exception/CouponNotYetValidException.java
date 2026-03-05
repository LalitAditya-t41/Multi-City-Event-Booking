package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BusinessRuleException;

public class CouponNotYetValidException extends BusinessRuleException {
  public CouponNotYetValidException(String code) {
    super("Coupon not yet valid: " + code, "COUPON_NOT_YET_VALID");
  }
}

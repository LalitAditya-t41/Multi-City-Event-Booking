package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CouponOrgMismatchException extends BaseException {
  public CouponOrgMismatchException() {
    super(
        "Coupon does not belong to cart organization", "COUPON_ORG_MISMATCH", HttpStatus.FORBIDDEN);
  }
}

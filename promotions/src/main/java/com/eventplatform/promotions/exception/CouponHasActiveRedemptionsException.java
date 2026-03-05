package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CouponHasActiveRedemptionsException extends BaseException {
  public CouponHasActiveRedemptionsException(String code) {
    super(
        "Coupon has active redemptions: " + code,
        "COUPON_HAS_ACTIVE_REDEMPTIONS",
        HttpStatus.CONFLICT);
  }
}

package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class CouponCodeConflictException extends BaseException {
    public CouponCodeConflictException(String code) {
        super("Coupon code already exists in org: " + code, "COUPON_CODE_CONFLICT", HttpStatus.CONFLICT);
    }
}

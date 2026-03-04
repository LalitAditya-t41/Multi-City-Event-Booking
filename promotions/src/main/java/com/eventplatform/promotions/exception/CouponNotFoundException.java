package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class CouponNotFoundException extends ResourceNotFoundException {
    public CouponNotFoundException(String code) {
        super("Coupon not found: " + code, "COUPON_NOT_FOUND");
    }
}

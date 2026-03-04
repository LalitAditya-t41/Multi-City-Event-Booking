package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BusinessRuleException;

public class CouponEventMismatchException extends BusinessRuleException {
    public CouponEventMismatchException() {
        super("Coupon is not valid for this event", "COUPON_EVENT_MISMATCH");
    }
}

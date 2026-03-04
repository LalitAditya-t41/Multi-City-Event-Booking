package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.ResourceNotFoundException;

public class PromotionNotFoundException extends ResourceNotFoundException {
    public PromotionNotFoundException(Long id) {
        super("Promotion not found: " + id, "PROMOTION_NOT_FOUND");
    }
}

package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class TierBlockedException extends BaseException {
    public TierBlockedException(Long tierId) {
        super("Tier temporarily blocked", "TIER_INVENTORY_BLOCKED", HttpStatus.CONFLICT, Map.of("tierId", tierId, "reason", "EB_SOLD_OUT_DETECTED"));
    }
}

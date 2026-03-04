package com.eventplatform.bookinginventory.exception;

import com.eventplatform.shared.common.exception.BaseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class TierSoldOutException extends BaseException {
    public TierSoldOutException(Long tierId, Object alternatives) {
        super("Tier sold out", "TIER_SOLD_OUT", HttpStatus.CONFLICT, Map.of("soldOutTierId", tierId, "alternatives", alternatives));
    }
}

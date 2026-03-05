package com.eventplatform.promotions.exception;

import com.eventplatform.shared.common.exception.BaseException;
import org.springframework.http.HttpStatus;

public class PromotionHasActiveRedemptionsException extends BaseException {
  public PromotionHasActiveRedemptionsException(Long promotionId) {
    super(
        "Promotion has active redemptions: " + promotionId,
        "PROMOTION_HAS_ACTIVE_REDEMPTIONS",
        HttpStatus.CONFLICT);
  }
}

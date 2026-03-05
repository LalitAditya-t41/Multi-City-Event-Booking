package com.eventplatform.promotions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventplatform.promotions.domain.enums.CouponStatus;
import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CouponTest {

  @Test
  void recordRedemption_should_transition_to_EXHAUSTED_when_count_reaches_max_usage_limit() {
    Coupon coupon = coupon();
    for (int i = 0; i < 4; i++) {
      coupon.recordRedemption(5);
    }

    coupon.recordRedemption(5);

    assertThat(coupon.getRedemptionCount()).isEqualTo(5);
    assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXHAUSTED);
  }

  @Test
  void voidRedemption_should_revert_EXHAUSTED_to_ACTIVE() {
    Coupon coupon = coupon();
    for (int i = 0; i < 5; i++) {
      coupon.recordRedemption(5);
    }
    assertThat(coupon.getStatus()).isEqualTo(CouponStatus.EXHAUSTED);

    coupon.voidRedemption();

    assertThat(coupon.getRedemptionCount()).isEqualTo(4);
    assertThat(coupon.getStatus()).isEqualTo(CouponStatus.ACTIVE);
  }

  @Test
  void canEbDelete_should_return_false_when_ebQuantitySoldAtLastSync_is_greater_than_zero() {
    Coupon coupon = coupon();
    coupon.markSynced("eb_123", 1);

    boolean canDelete = coupon.canEbDelete();

    assertThat(canDelete).isFalse();
  }

  private Coupon coupon() {
    Promotion promotion =
        new Promotion(
            1L,
            "Promo",
            DiscountType.PERCENT_OFF,
            new BigDecimal("10"),
            PromotionScope.ORG_WIDE,
            null,
            5,
            2,
            Instant.now().minusSeconds(3600),
            Instant.now().plusSeconds(3600));
    return new Coupon(promotion, 1L, "SAVE10");
  }
}

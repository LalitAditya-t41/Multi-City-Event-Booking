package com.eventplatform.promotions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventplatform.promotions.domain.enums.DiscountType;
import com.eventplatform.promotions.domain.enums.PromotionScope;
import com.eventplatform.promotions.domain.enums.PromotionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PromotionTest {

  @Test
  void deactivate_should_cascade_inactive_status_to_all_linked_coupons() {
    Promotion promotion =
        promotion(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
    Coupon c1 = new Coupon(promotion, 1L, "SAVE10");
    Coupon c2 = new Coupon(promotion, 1L, "SAVE20");
    promotion.addCoupon(c1);
    promotion.addCoupon(c2);

    promotion.deactivate();

    assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.INACTIVE);
    assertThat(c1.getStatus())
        .isEqualTo(com.eventplatform.promotions.domain.enums.CouponStatus.INACTIVE);
    assertThat(c2.getStatus())
        .isEqualTo(com.eventplatform.promotions.domain.enums.CouponStatus.INACTIVE);
  }

  @Test
  void isValid_should_return_false_when_validUntil_is_in_past() {
    Promotion promotion =
        promotion(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

    boolean valid = promotion.isValid(Instant.now());

    assertThat(valid).isFalse();
  }

  @Test
  void isValid_should_return_false_when_validFrom_is_in_future() {
    Promotion promotion =
        promotion(Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200));

    boolean valid = promotion.isValid(Instant.now());

    assertThat(valid).isFalse();
  }

  private Promotion promotion(Instant from, Instant until) {
    return new Promotion(
        1L,
        "Promo",
        DiscountType.PERCENT_OFF,
        new BigDecimal("10"),
        PromotionScope.ORG_WIDE,
        null,
        10,
        2,
        from,
        until);
  }
}

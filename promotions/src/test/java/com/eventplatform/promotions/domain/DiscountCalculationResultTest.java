package com.eventplatform.promotions.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class DiscountCalculationResultTest {

    @Test
    void percentOff_should_compute_discount_correctly_in_smallest_unit() {
        long cartTotal = 10_000L;
        BigDecimal percent = new BigDecimal("10");

        long discount = BigDecimal.valueOf(cartTotal)
            .multiply(percent)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.FLOOR)
            .longValue();
        long adjusted = cartTotal - discount;

        assertThat(discount).isEqualTo(1_000L);
        assertThat(adjusted).isEqualTo(9_000L);
    }

    @Test
    void amountOff_should_floor_to_zero_when_discount_exceeds_cart_total() {
        long cartTotal = 500L;
        long amountOff = 1_000L;

        long discount = Math.min(amountOff, cartTotal);
        long adjusted = Math.max(0L, cartTotal - discount);

        assertThat(discount).isEqualTo(500L);
        assertThat(adjusted).isEqualTo(0L);
    }
}

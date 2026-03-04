package com.eventplatform.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShowSlotPricingTierTest {

    @Test
    void should_allow_zero_priceAmount_for_FREE_tier() {
        new ShowSlotPricingTier("Free", new Money(BigDecimal.ZERO, "INR"), 5, TierType.FREE);
    }

    @Test
    void should_throw_BusinessRuleException_when_FREE_tier_has_non_zero_priceAmount() {
        assertThatThrownBy(() ->
            new ShowSlotPricingTier("Free", new Money(new BigDecimal("10.00"), "INR"), 5, TierType.FREE)
        ).isInstanceOf(BusinessRuleException.class);
    }

    // --- Group Discount: setGroupDiscount ---

    @Test
    void should_set_group_discount_when_valid_threshold_and_percent() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);

        tier.setGroupDiscount(5, new BigDecimal("10.00"));

        assertThat(tier.getGroupDiscountThreshold()).isEqualTo(5);
        assertThat(tier.getGroupDiscountPercent()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void should_allow_null_group_discount_to_clear_discount() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);
        tier.setGroupDiscount(5, new BigDecimal("10.00"));

        tier.setGroupDiscount(null, null);

        assertThat(tier.getGroupDiscountThreshold()).isNull();
        assertThat(tier.getGroupDiscountPercent()).isNull();
    }

    @Test
    void should_throw_when_group_discount_threshold_is_zero() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);

        assertThatThrownBy(() -> tier.setGroupDiscount(0, new BigDecimal("10.00")))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("threshold");
    }

    @Test
    void should_throw_when_group_discount_threshold_is_negative() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);

        assertThatThrownBy(() -> tier.setGroupDiscount(-1, new BigDecimal("10.00")))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void should_throw_when_group_discount_percent_is_zero() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);

        assertThatThrownBy(() -> tier.setGroupDiscount(5, BigDecimal.ZERO))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("percent");
    }

    @Test
    void should_throw_when_group_discount_percent_exceeds_100() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);

        assertThatThrownBy(() -> tier.setGroupDiscount(5, new BigDecimal("100.01")))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("percent");
    }

    @Test
    void should_allow_group_discount_percent_of_exactly_100() {
        ShowSlotPricingTier tier = new ShowSlotPricingTier("VIP", new Money(new BigDecimal("500"), "INR"), 10, TierType.PAID);

        tier.setGroupDiscount(2, new BigDecimal("100"));

        assertThat(tier.getGroupDiscountPercent()).isEqualByComparingTo(new BigDecimal("100"));
    }
}


package com.eventplatform.scheduling.domain;

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
}

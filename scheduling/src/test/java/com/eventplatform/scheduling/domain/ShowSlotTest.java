package com.eventplatform.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.scheduling.domain.enums.ShowSlotStatus;
import com.eventplatform.scheduling.domain.enums.TierType;
import com.eventplatform.shared.common.domain.Money;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class ShowSlotTest {

  @Test
  void should_transition_to_PENDING_SYNC_when_slot_is_DRAFT_and_has_pricing_tiers() {
    ShowSlot slot = baseSlot();
    slot.addPricingTier(
        new ShowSlotPricingTier("General", new Money(BigDecimal.ZERO, "INR"), 10, TierType.FREE));

    slot.submit();

    assertThat(slot.getStatus()).isEqualTo(ShowSlotStatus.PENDING_SYNC);
  }

  @Test
  void should_transition_to_ACTIVE_when_slot_is_PENDING_SYNC() {
    ShowSlot slot = baseSlot();
    slot.addPricingTier(
        new ShowSlotPricingTier("General", new Money(BigDecimal.ZERO, "INR"), 10, TierType.FREE));
    slot.markPendingSync();

    slot.activate();

    assertThat(slot.getStatus()).isEqualTo(ShowSlotStatus.ACTIVE);
  }

  @Test
  void should_transition_to_CANCELLED_when_slot_is_ACTIVE() {
    ShowSlot slot = baseSlot();
    slot.markActive();

    slot.cancel();

    assertThat(slot.getStatus()).isEqualTo(ShowSlotStatus.CANCELLED);
  }

  @Test
  void should_throw_BusinessRuleException_when_submitting_non_DRAFT_slot() {
    ShowSlot slot = baseSlot();
    slot.markPendingSync();

    assertThatThrownBy(slot::submit).isInstanceOf(BusinessRuleException.class);
  }

  @Test
  void should_throw_BusinessRuleException_when_transitioning_CANCELLED_slot_to_any_state() {
    ShowSlot slot = baseSlot();
    slot.markCancelled();

    assertThatThrownBy(slot::cancel).isInstanceOf(BusinessRuleException.class);
  }

  private ShowSlot baseSlot() {
    return new ShowSlot(
        1L,
        10L,
        20L,
        "Show",
        "Desc",
        ZonedDateTime.now().plusDays(1),
        ZonedDateTime.now().plusDays(1).plusHours(2),
        SeatingMode.GA,
        100,
        false,
        null,
        null);
  }
}

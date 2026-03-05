package com.eventplatform.paymentsticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.paymentsticketing.domain.enums.BookingItemStatus;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

class BookingItemTest {

  @Test
  void should_transition_active_to_cancelled_when_cancel_called() {
    BookingItem bookingItem = new BookingItem(1L, 10L, null, "TC-1", 50000L, "inr");

    bookingItem.cancel();

    assertThat(bookingItem.getStatus()).isEqualTo(BookingItemStatus.CANCELLED);
  }

  @Test
  void should_throw_business_rule_exception_when_cancel_called_for_already_cancelled_item() {
    BookingItem bookingItem = new BookingItem(1L, 10L, null, "TC-1", 50000L, "inr");
    bookingItem.cancel();

    assertThatThrownBy(bookingItem::cancel)
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("already cancelled");
  }
}

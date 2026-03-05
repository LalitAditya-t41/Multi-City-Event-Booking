package com.eventplatform.paymentsticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

class BookingTest {

  @Test
  void should_transition_pending_to_confirmed_when_confirm_called() {
    Booking booking = new Booking("BK-20260304-001", 1L, 2L, 3L, 4L, 5000L, "inr");

    booking.confirm("pi_123", "ch_123");

    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(booking.getStripePaymentIntentId()).isEqualTo("pi_123");
    assertThat(booking.getStripeChargeId()).isEqualTo("ch_123");
  }

  @Test
  void should_transition_confirmed_to_cancellation_pending_when_mark_cancellation_pending_called() {
    Booking booking = new Booking("BK-20260304-001", 1L, 2L, 3L, 4L, 5000L, "inr");
    booking.confirm("pi_123", "ch_123");

    booking.markCancellationPending();

    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
  }

  @Test
  void should_transition_cancellation_pending_to_cancelled_when_cancel_called() {
    Booking booking = new Booking("BK-20260304-001", 1L, 2L, 3L, 4L, 5000L, "inr");
    booking.confirm("pi_123", "ch_123");
    booking.markCancellationPending();

    booking.cancel();

    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
  }

  @Test
  void should_throw_when_transitioning_confirmed_to_cancelled_directly() {
    Booking booking = new Booking("BK-20260304-001", 1L, 2L, 3L, 4L, 5000L, "inr");
    booking.confirm("pi_123", "ch_123");

    assertThatThrownBy(booking::cancel)
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("cannot be cancelled");
  }

  @Test
  void should_throw_when_transitioning_cancelled_to_any_state() {
    Booking booking = new Booking("BK-20260304-001", 1L, 2L, 3L, 4L, 5000L, "inr");
    booking.confirm("pi_123", "ch_123");
    booking.markCancellationPending();
    booking.cancel();

    assertThatThrownBy(() -> booking.confirm("pi_999", "ch_999"))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(booking::markCancellationPending).isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(booking::cancel).isInstanceOf(BusinessRuleException.class);
  }
}

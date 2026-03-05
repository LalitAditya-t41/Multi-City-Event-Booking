package com.eventplatform.engagement.event.listener;

import com.eventplatform.engagement.service.ReviewEligibilityService;
import com.eventplatform.shared.common.event.published.BookingCancelledEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BookingCancelledListener {

  private final ReviewEligibilityService reviewEligibilityService;

  public BookingCancelledListener(ReviewEligibilityService reviewEligibilityService) {
    this.reviewEligibilityService = reviewEligibilityService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBookingCancelled(BookingCancelledEvent event) {
    reviewEligibilityService.revokeForBooking(event.bookingId());
  }
}

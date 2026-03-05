package com.eventplatform.engagement.event.listener;

import com.eventplatform.engagement.service.ReviewEligibilityService;
import com.eventplatform.shared.common.event.published.BookingConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component("engagementBookingConfirmedListener")
public class BookingConfirmedListener {

  private final ReviewEligibilityService reviewEligibilityService;

  public BookingConfirmedListener(ReviewEligibilityService reviewEligibilityService) {
    this.reviewEligibilityService = reviewEligibilityService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBookingConfirmed(BookingConfirmedEvent event) {
    reviewEligibilityService.unlockForBooking(event.bookingId(), event.userId());
  }
}

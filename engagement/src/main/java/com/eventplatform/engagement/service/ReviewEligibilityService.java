package com.eventplatform.engagement.service;

import com.eventplatform.engagement.domain.Review;
import com.eventplatform.engagement.domain.ReviewEligibility;
import com.eventplatform.engagement.domain.enums.ReviewEligibilityStatus;
import com.eventplatform.engagement.domain.enums.ReviewStatus;
import com.eventplatform.engagement.exception.ReviewNotEligibleException;
import com.eventplatform.engagement.repository.ReviewEligibilityRepository;
import com.eventplatform.engagement.repository.ReviewRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewEligibilityService {

  private static final Logger log = LoggerFactory.getLogger(ReviewEligibilityService.class);

  private final ReviewEligibilityRepository reviewEligibilityRepository;
  private final ReviewRepository reviewRepository;
  private final PaymentBookingLookupService paymentBookingLookupService;
  private final Integer eligibilityWindowDays;

  public ReviewEligibilityService(
      ReviewEligibilityRepository reviewEligibilityRepository,
      ReviewRepository reviewRepository,
      PaymentBookingLookupService paymentBookingLookupService,
      @Value("${engagement.review.eligibility-window-days:0}") Integer eligibilityWindowDays) {
    this.reviewEligibilityRepository = reviewEligibilityRepository;
    this.reviewRepository = reviewRepository;
    this.paymentBookingLookupService = paymentBookingLookupService;
    this.eligibilityWindowDays = eligibilityWindowDays;
  }

  @Transactional
  public void unlockForBooking(Long bookingId, Long userId) {
    PaymentBookingLookupService.BookingSnapshot booking =
        paymentBookingLookupService.getBookingById(bookingId);
    if (booking == null || booking.eventId() == null) {
      log.warn(
          "Cannot unlock review eligibility because booking lookup failed. bookingId={} userId={}",
          bookingId,
          userId);
      return;
    }

    Instant eligibleUntil = null;
    if (eligibilityWindowDays != null && eligibilityWindowDays > 0) {
      eligibleUntil = Instant.now().plus(eligibilityWindowDays, ChronoUnit.DAYS);
    }

    Optional<ReviewEligibility> existing =
        reviewEligibilityRepository.findByUserIdAndEventId(userId, booking.eventId());
    if (existing.isPresent()) {
      ReviewEligibility eligibility = existing.get();
      eligibility.unlock(booking.slotId(), bookingId, eligibleUntil);
      reviewEligibilityRepository.save(eligibility);
      return;
    }

    reviewEligibilityRepository.save(
        new ReviewEligibility(
            userId, booking.eventId(), booking.slotId(), bookingId, eligibleUntil));
  }

  @Transactional
  public void revokeForBooking(Long bookingId) {
    Optional<ReviewEligibility> eligibilityOpt =
        reviewEligibilityRepository.findByBookingId(bookingId);
    if (eligibilityOpt.isEmpty()) {
      return;
    }

    ReviewEligibility eligibility = eligibilityOpt.get();
    PaymentBookingLookupService.BookingSnapshot booking =
        paymentBookingLookupService.getBookingById(bookingId);
    if (booking == null || !"CANCELLED".equalsIgnoreCase(booking.status())) {
      return;
    }

    Optional<Review> reviewOpt =
        reviewRepository.findByUserIdAndEventId(eligibility.getUserId(), eligibility.getEventId());
    if (reviewOpt.isPresent()) {
      Review review = reviewOpt.get();
      if (review.getStatus() == ReviewStatus.PUBLISHED) {
        return;
      }
      if (review.getStatus() == ReviewStatus.PENDING_MODERATION) {
        review.reject("Booking cancelled");
        reviewRepository.save(review);
      }
    }

    eligibility.revoke();
    reviewEligibilityRepository.save(eligibility);
  }

  @Transactional
  public ReviewEligibility validateEligibility(Long userId, Long eventId) {
    ReviewEligibility eligibility =
        reviewEligibilityRepository
            .findByUserIdAndEventId(userId, eventId)
            .orElseThrow(
                () ->
                    new ReviewNotEligibleException(
                        "You are not eligible to review this event", "REVIEW_NOT_ELIGIBLE"));

    if (eligibility.getStatus() != ReviewEligibilityStatus.UNLOCKED) {
      throw new ReviewNotEligibleException(
          "You are not eligible to review this event", "REVIEW_NOT_ELIGIBLE");
    }

    if (eligibility.getEligibleUntil() != null
        && eligibility.getEligibleUntil().isBefore(Instant.now())) {
      eligibility.markExpired();
      reviewEligibilityRepository.save(eligibility);
      throw new ReviewNotEligibleException(
          "Review submission window has closed", "REVIEW_WINDOW_CLOSED");
    }

    return eligibility;
  }
}

package com.eventplatform.engagement.domain;

import com.eventplatform.engagement.domain.enums.ReviewEligibilityStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "review_eligibility")
public class ReviewEligibility extends BaseEntity {

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "event_id", nullable = false)
  private Long eventId;

  @Column(name = "slot_id")
  private Long slotId;

  @Column(name = "booking_id")
  private Long bookingId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private ReviewEligibilityStatus status;

  @Column(name = "eligible_until")
  private Instant eligibleUntil;

  protected ReviewEligibility() {}

  public ReviewEligibility(
      Long userId, Long eventId, Long slotId, Long bookingId, Instant eligibleUntil) {
    this.userId = userId;
    this.eventId = eventId;
    this.slotId = slotId;
    this.bookingId = bookingId;
    this.eligibleUntil = eligibleUntil;
    this.status = ReviewEligibilityStatus.UNLOCKED;
  }

  public void unlock(Long slotId, Long bookingId, Instant eligibleUntil) {
    if (this.status == ReviewEligibilityStatus.REVOKED) {
      return;
    }
    this.slotId = slotId;
    this.bookingId = bookingId;
    this.eligibleUntil = eligibleUntil;
    this.status = ReviewEligibilityStatus.UNLOCKED;
  }

  public void revoke() {
    if (this.status == ReviewEligibilityStatus.REVOKED) {
      return;
    }
    this.status = ReviewEligibilityStatus.REVOKED;
  }

  public void markExpired() {
    this.status = ReviewEligibilityStatus.EXPIRED;
  }

  public boolean isEligible(Instant now) {
    if (status != ReviewEligibilityStatus.UNLOCKED) {
      return false;
    }
    return eligibleUntil == null || !eligibleUntil.isBefore(now);
  }

  public Long getUserId() {
    return userId;
  }

  public Long getEventId() {
    return eventId;
  }

  public Long getSlotId() {
    return slotId;
  }

  public Long getBookingId() {
    return bookingId;
  }

  public ReviewEligibilityStatus getStatus() {
    return status;
  }

  public Instant getEligibleUntil() {
    return eligibleUntil;
  }
}

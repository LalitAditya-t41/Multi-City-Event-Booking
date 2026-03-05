package com.eventplatform.bookinginventory.domain;

import com.eventplatform.bookinginventory.domain.enums.LockReleaseReason;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(
    name = "seats",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_seat_position",
            columnNames = {"show_slot_id", "seat_number"}))
public class Seat extends BaseEntity {

  @Column(name = "show_slot_id", nullable = false)
  private Long showSlotId;

  @Column(name = "pricing_tier_id", nullable = false)
  private Long pricingTierId;

  @Column(name = "eb_ticket_class_id", nullable = false)
  private String ebTicketClassId;

  @Column(name = "seat_number", nullable = false)
  private String seatNumber;

  @Column(name = "row_label")
  private String rowLabel;

  @Column(name = "section")
  private String section;

  @Enumerated(EnumType.STRING)
  @Column(name = "lock_state", nullable = false)
  private SeatLockState lockState;

  @Column(name = "locked_by_user_id")
  private Long lockedByUserId;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "booking_ref")
  private String bookingRef;

  protected Seat() {}

  public Seat(
      Long showSlotId,
      Long pricingTierId,
      String ebTicketClassId,
      String seatNumber,
      String rowLabel,
      String section) {
    this.showSlotId = showSlotId;
    this.pricingTierId = pricingTierId;
    this.ebTicketClassId = ebTicketClassId;
    this.seatNumber = seatNumber;
    this.rowLabel = rowLabel;
    this.section = section;
    this.lockState = SeatLockState.AVAILABLE;
  }

  public void softLock(Long userId, Duration ttl) {
    if (!isSelectable(Instant.now())) {
      throw new BusinessRuleException("Seat is not available for soft lock", "SEAT_UNAVAILABLE");
    }
    this.lockState = SeatLockState.SOFT_LOCKED;
    this.lockedByUserId = userId;
    this.lockedUntil = Instant.now().plus(ttl);
  }

  public void hardLock(Duration ttl) {
    if (this.lockState != SeatLockState.SOFT_LOCKED) {
      throw new BusinessRuleException("Seat is not soft-locked", "INVALID_SEAT_TRANSITION");
    }
    this.lockState = SeatLockState.HARD_LOCKED;
    this.lockedUntil = Instant.now().plus(ttl);
  }

  public void markPaymentPending() {
    if (this.lockState != SeatLockState.HARD_LOCKED) {
      throw new BusinessRuleException("Seat is not hard-locked", "INVALID_SEAT_TRANSITION");
    }
    this.lockState = SeatLockState.PAYMENT_PENDING;
  }

  public void confirm(String bookingRef) {
    if (this.lockState != SeatLockState.PAYMENT_PENDING
        && this.lockState != SeatLockState.HARD_LOCKED) {
      throw new BusinessRuleException(
          "Seat cannot be confirmed from current state", "INVALID_SEAT_TRANSITION");
    }
    this.lockState = SeatLockState.CONFIRMED;
    this.bookingRef = bookingRef;
    this.lockedByUserId = null;
    this.lockedUntil = null;
  }

  public void release(LockReleaseReason reason) {
    if (this.lockState == SeatLockState.CONFIRMED) {
      throw new BusinessRuleException(
          "Confirmed seat cannot be released", "SEAT_ALREADY_CONFIRMED");
    }
    this.lockState = SeatLockState.AVAILABLE;
    this.lockedByUserId = null;
    this.lockedUntil = null;
  }

  public boolean isSelectable(Instant now) {
    if (this.lockState == SeatLockState.AVAILABLE) {
      return true;
    }
    return this.lockState == SeatLockState.SOFT_LOCKED
        && this.lockedUntil != null
        && this.lockedUntil.isBefore(now);
  }

  public Long getShowSlotId() {
    return showSlotId;
  }

  public Long getPricingTierId() {
    return pricingTierId;
  }

  public String getEbTicketClassId() {
    return ebTicketClassId;
  }

  public String getSeatNumber() {
    return seatNumber;
  }

  public String getRowLabel() {
    return rowLabel;
  }

  public String getSection() {
    return section;
  }

  public SeatLockState getLockState() {
    return lockState;
  }

  public Long getLockedByUserId() {
    return lockedByUserId;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public String getBookingRef() {
    return bookingRef;
  }
}

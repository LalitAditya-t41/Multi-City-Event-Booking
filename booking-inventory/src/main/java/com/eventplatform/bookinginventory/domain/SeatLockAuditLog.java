package com.eventplatform.bookinginventory.domain;

import com.eventplatform.bookinginventory.domain.enums.SeatLockEvent;
import com.eventplatform.bookinginventory.domain.enums.SeatLockState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "seat_lock_audit_log")
public class SeatLockAuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "seat_id")
  private Long seatId;

  @Column(name = "ga_claim_id")
  private Long gaClaimId;

  @Column(name = "show_slot_id", nullable = false)
  private Long showSlotId;

  @Column(name = "user_id")
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_state")
  private SeatLockState fromState;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_state", nullable = false)
  private SeatLockState toState;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false)
  private SeatLockEvent eventType;

  @Column(name = "reason")
  private String reason;

  @Column(name = "booking_ref")
  private String bookingRef;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected SeatLockAuditLog() {}

  public SeatLockAuditLog(
      Long seatId,
      Long gaClaimId,
      Long showSlotId,
      Long userId,
      SeatLockState fromState,
      SeatLockState toState,
      SeatLockEvent eventType,
      String reason,
      String bookingRef) {
    this.seatId = seatId;
    this.gaClaimId = gaClaimId;
    this.showSlotId = showSlotId;
    this.userId = userId;
    this.fromState = fromState;
    this.toState = toState;
    this.eventType = eventType;
    this.reason = reason;
    this.bookingRef = bookingRef;
  }

  @PrePersist
  void prePersist() {
    if (occurredAt == null) {
      occurredAt = Instant.now();
    }
  }
}

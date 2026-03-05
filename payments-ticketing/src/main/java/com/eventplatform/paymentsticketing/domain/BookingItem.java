package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.BookingItemStatus;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "booking_items")
public class BookingItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "booking_id", nullable = false)
  private Long bookingId;

  @Column(name = "seat_id")
  private Long seatId;

  @Column(name = "ga_claim_id")
  private Long gaClaimId;

  @Column(name = "ticket_class_id", nullable = false)
  private String ticketClassId;

  @Column(name = "unit_price", nullable = false)
  private Long unitPrice;

  @Column(name = "currency", nullable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private BookingItemStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected BookingItem() {}

  public BookingItem(
      Long bookingId,
      Long seatId,
      Long gaClaimId,
      String ticketClassId,
      Long unitPrice,
      String currency) {
    this.bookingId = bookingId;
    this.seatId = seatId;
    this.gaClaimId = gaClaimId;
    this.ticketClassId = ticketClassId;
    this.unitPrice = unitPrice;
    this.currency = currency;
    this.status = BookingItemStatus.ACTIVE;
    this.createdAt = Instant.now();
  }

  public void cancel() {
    if (this.status == BookingItemStatus.CANCELLED) {
      throw new BusinessRuleException(
          "Booking item already cancelled", "DUPLICATE_ITEM_CANCELLATION");
    }
    this.status = BookingItemStatus.CANCELLED;
  }

  public Long getId() {
    return id;
  }

  public Long getBookingId() {
    return bookingId;
  }

  public Long getSeatId() {
    return seatId;
  }

  public Long getGaClaimId() {
    return gaClaimId;
  }

  public String getTicketClassId() {
    return ticketClassId;
  }

  public Long getUnitPrice() {
    return unitPrice;
  }

  public String getCurrency() {
    return currency;
  }

  public BookingItemStatus getStatus() {
    return status;
  }
}

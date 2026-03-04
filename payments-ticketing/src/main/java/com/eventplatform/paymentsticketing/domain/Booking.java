package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Represents a confirmed purchase — one record per cart confirmation.
 */
@Entity
@Table(name = "bookings")
public class Booking extends BaseEntity {

    @Column(name = "booking_ref", nullable = false, unique = true)
    private String bookingRef;

    @Column(name = "cart_id", nullable = false)
    private Long cartId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "stripe_charge_id")
    private String stripeChargeId;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    protected Booking() {}

    public Booking(String bookingRef, Long cartId, Long userId, Long eventId,
                   Long slotId, Long totalAmount, String currency) {
        this.bookingRef = bookingRef;
        this.cartId = cartId;
        this.userId = userId;
        this.eventId = eventId;
        this.slotId = slotId;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = BookingStatus.PENDING;
    }

    public void confirm(String stripePaymentIntentId, String stripeChargeId) {
        this.status = BookingStatus.CONFIRMED;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.stripeChargeId = stripeChargeId;
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    public String getBookingRef() { return bookingRef; }
    public Long getCartId() { return cartId; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public Long getSlotId() { return slotId; }
    public BookingStatus getStatus() { return status; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public String getStripeChargeId() { return stripeChargeId; }
    public Long getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
}

package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

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

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "slot_start_time")
    private Instant slotStartTime;

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

    protected Booking() {
    }

    public Booking(
        String bookingRef,
        Long cartId,
        Long userId,
        Long eventId,
        Long slotId,
        Long orgId,
        Instant slotStartTime,
        Long totalAmount,
        String currency
    ) {
        this.bookingRef = bookingRef;
        this.cartId = cartId;
        this.userId = userId;
        this.eventId = eventId;
        this.slotId = slotId;
        this.orgId = orgId;
        this.slotStartTime = slotStartTime;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.status = BookingStatus.PENDING;
    }

    public Booking(String bookingRef, Long cartId, Long userId, Long eventId, Long slotId, Long totalAmount, String currency) {
        this(bookingRef, cartId, userId, eventId, slotId, null, null, totalAmount, currency);
    }

    public void confirm(String stripePaymentIntentId, String stripeChargeId) {
        if (status != BookingStatus.PENDING) {
            throw new BusinessRuleException("Booking cannot be confirmed from state " + status, "INVALID_BOOKING_STATE");
        }
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.stripeChargeId = stripeChargeId;
        this.status = BookingStatus.CONFIRMED;
    }

    public void markCancellationPending() {
        if (status != BookingStatus.CONFIRMED) {
            throw new BusinessRuleException("Booking cannot enter cancellation pending from state " + status, "INVALID_BOOKING_STATE");
        }
        this.status = BookingStatus.CANCELLATION_PENDING;
    }

    public void revertCancellationPending() {
        if (status == BookingStatus.CANCELLATION_PENDING) {
            this.status = BookingStatus.CONFIRMED;
        }
    }

    public void cancel() {
        if (status != BookingStatus.CANCELLATION_PENDING) {
            throw new BusinessRuleException("Booking cannot be cancelled from state " + status, "INVALID_BOOKING_STATE");
        }
        this.status = BookingStatus.CANCELLED;
    }

    public void cancelFromConfirmed() {
        if (status == BookingStatus.CANCELLED) {
            return;
        }
        if (status != BookingStatus.CONFIRMED && status != BookingStatus.CANCELLATION_PENDING) {
            throw new BusinessRuleException("Booking cannot be cancelled from state " + status, "INVALID_BOOKING_STATE");
        }
        this.status = BookingStatus.CANCELLED;
    }

    public void updateTotalAmount(Long totalAmount) {
        this.totalAmount = totalAmount;
    }

    public void cancelDueToPaymentFailure() {
        if (status == BookingStatus.CANCELLED) {
            return;
        }
        if (status == BookingStatus.PENDING || status == BookingStatus.CANCELLATION_PENDING || status == BookingStatus.CONFIRMED) {
            this.status = BookingStatus.CANCELLED;
            return;
        }
        throw new BusinessRuleException("Booking cannot be cancelled from state " + status, "INVALID_BOOKING_STATE");
    }

    public String getBookingRef() {
        return bookingRef;
    }

    public Long getCartId() {
        return cartId;
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

    public Long getOrgId() {
        return orgId;
    }

    public Instant getSlotStartTime() {
        return slotStartTime;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    public String getStripeChargeId() {
        return stripeChargeId;
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }
}

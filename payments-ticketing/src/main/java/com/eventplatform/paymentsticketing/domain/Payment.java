package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.PaymentStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Represents a Stripe payment record linked to a Booking.
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "stripe_payment_intent_id", nullable = false, unique = true)
    private String stripePaymentIntentId;

    @Column(name = "stripe_charge_id")
    private String stripeChargeId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_message")
    private String failureMessage;

    protected Payment() {}

    public Payment(Long bookingId, String stripePaymentIntentId, Long amount, String currency) {
        this.bookingId = bookingId;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
    }

    public void markSucceeded(String stripeChargeId) {
        this.status = PaymentStatus.SUCCEEDED;
        this.stripeChargeId = stripeChargeId;
    }

    public void markFailed(String failureCode, String failureMessage) {
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    public void markCancelled() {
        this.status = PaymentStatus.CANCELLED;
    }

    public Long getBookingId() { return bookingId; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public String getStripeChargeId() { return stripeChargeId; }
    public Long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
}

package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.PaymentStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

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

    protected Payment() {
    }

    public Payment(Long bookingId, String stripePaymentIntentId, Long amount, String currency) {
        this.bookingId = bookingId;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
    }

    public void markSuccess(String stripeChargeId) {
        if (status == PaymentStatus.SUCCESS) {
            return;
        }
        if (status == PaymentStatus.FAILED) {
            throw new BusinessRuleException("Payment cannot move from FAILED to SUCCESS", "INVALID_PAYMENT_STATE");
        }
        this.status = PaymentStatus.SUCCESS;
        this.stripeChargeId = stripeChargeId;
    }

    public void markFailed(String failureCode, String failureMessage) {
        if (status == PaymentStatus.SUCCESS) {
            throw new BusinessRuleException("Payment cannot move from SUCCESS to FAILED", "INVALID_PAYMENT_STATE");
        }
        this.status = PaymentStatus.FAILED;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    public String getStripeChargeId() {
        return stripeChargeId;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }
}

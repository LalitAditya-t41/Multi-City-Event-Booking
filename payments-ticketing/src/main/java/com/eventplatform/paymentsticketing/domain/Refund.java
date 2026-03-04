package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.shared.common.domain.BaseEntity;
import com.eventplatform.shared.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "refunds")
public class Refund extends BaseEntity {

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "stripe_refund_id", unique = true)
    private String stripeRefundId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RefundStatus status;

    protected Refund() {
    }

    public Refund(Long bookingId, String stripeRefundId, Long amount, String currency, RefundReason reason, RefundStatus status) {
        this.bookingId = bookingId;
        this.stripeRefundId = stripeRefundId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.status = status;
    }

    public void updateStatus(RefundStatus status) {
        if (this.status == RefundStatus.SUCCEEDED && status != RefundStatus.SUCCEEDED) {
            throw new BusinessRuleException("Refund is terminal in SUCCEEDED state", "INVALID_REFUND_STATE");
        }
        this.status = status;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public String getStripeRefundId() {
        return stripeRefundId;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundReason getReason() {
        return reason;
    }

    public RefundStatus getStatus() {
        return status;
    }
}

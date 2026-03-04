package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.EventCancellationAuditStatus;
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
@Table(name = "event_cancellation_refund_audits")
public class EventCancellationRefundAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "refund_id")
    private Long refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EventCancellationAuditStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EventCancellationRefundAudit() {
    }

    public EventCancellationRefundAudit(Long slotId, Long bookingId) {
        this.slotId = slotId;
        this.bookingId = bookingId;
        this.status = EventCancellationAuditStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markPending() {
        this.status = EventCancellationAuditStatus.PENDING;
        this.errorMessage = null;
        this.processedAt = null;
    }

    public void markSucceeded(Long refundId) {
        this.status = EventCancellationAuditStatus.SUCCEEDED;
        this.refundId = refundId;
        this.errorMessage = null;
        this.processedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = EventCancellationAuditStatus.FAILED;
        this.errorMessage = errorMessage;
        this.processedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSlotId() {
        return slotId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getRefundId() {
        return refundId;
    }

    public EventCancellationAuditStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

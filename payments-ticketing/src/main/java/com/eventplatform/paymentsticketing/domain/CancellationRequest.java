package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.CancellationRequestStatus;
import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
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
@Table(name = "cancellation_requests")
public class CancellationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason")
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CancellationRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected CancellationRequest() {
    }

    public CancellationRequest(Long bookingId, Long userId, RefundReason reason) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.reason = reason;
        this.status = CancellationRequestStatus.PENDING;
        this.requestedAt = Instant.now();
    }

    public void approve() {
        this.status = CancellationRequestStatus.APPROVED;
        this.resolvedAt = Instant.now();
    }

    public void reject() {
        this.status = CancellationRequestStatus.REJECTED;
        this.resolvedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getUserId() {
        return userId;
    }

    public RefundReason getReason() {
        return reason;
    }

    public CancellationRequestStatus getStatus() {
        return status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}

package com.eventplatform.paymentsticketing.domain;

import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;
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
@Table(name = "e_tickets")
public class ETicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "booking_item_id", nullable = false)
    private Long bookingItemId;

    @Column(name = "qr_code_data", nullable = false)
    private String qrCodeData;

    @Column(name = "pdf_url")
    private String pdfUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ETicketStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected ETicket() {
    }

    public ETicket(Long bookingId, Long bookingItemId, String qrCodeData, String pdfUrl) {
        this.bookingId = bookingId;
        this.bookingItemId = bookingItemId;
        this.qrCodeData = qrCodeData;
        this.pdfUrl = pdfUrl;
        this.status = ETicketStatus.ACTIVE;
        this.issuedAt = Instant.now();
    }

    public void voidTicket() {
        this.status = ETicketStatus.VOIDED;
    }

    public Long getId() {
        return id;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getBookingItemId() {
        return bookingItemId;
    }

    public String getQrCodeData() {
        return qrCodeData;
    }

    public String getPdfUrl() {
        return pdfUrl;
    }

    public ETicketStatus getStatus() {
        return status;
    }
}

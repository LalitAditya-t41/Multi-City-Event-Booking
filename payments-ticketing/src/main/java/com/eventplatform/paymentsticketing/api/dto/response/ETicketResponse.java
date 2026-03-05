package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;

public record ETicketResponse(
    String ticketCode,
    Long bookingItemId,
    String qrCodeData,
    String pdfUrl,
    ETicketStatus status) {}

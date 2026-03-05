package com.eventplatform.paymentsticketing.api.dto.response;

import com.eventplatform.paymentsticketing.domain.enums.BookingItemStatus;

public record BookingItemResponse(
    Long bookingItemId,
    Long seatId,
    Long gaClaimId,
    String ticketClassId,
    Long unitPrice,
    String currency,
    BookingItemStatus status,
    ETicketResponse eTicket) {}

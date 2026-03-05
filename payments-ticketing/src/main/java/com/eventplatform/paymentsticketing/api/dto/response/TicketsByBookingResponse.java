package com.eventplatform.paymentsticketing.api.dto.response;

import java.util.List;

public record TicketsByBookingResponse(String bookingRef, List<ETicketResponse> tickets) {}

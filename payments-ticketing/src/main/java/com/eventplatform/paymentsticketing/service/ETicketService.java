package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.api.dto.response.ETicketResponse;
import com.eventplatform.paymentsticketing.api.dto.response.TicketsByBookingResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.mapper.ETicketMapper;
import com.eventplatform.paymentsticketing.repository.ETicketRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ETicketService {

    private final BookingService bookingService;
    private final ETicketRepository eTicketRepository;
    private final ETicketMapper eTicketMapper;

    public ETicketService(BookingService bookingService, ETicketRepository eTicketRepository, ETicketMapper eTicketMapper) {
        this.bookingService = bookingService;
        this.eTicketRepository = eTicketRepository;
        this.eTicketMapper = eTicketMapper;
    }

    @Transactional(readOnly = true)
    public TicketsByBookingResponse getTickets(Long userId, String bookingRef) {
        Booking booking = bookingService.getOwnedBooking(userId, bookingRef);

        List<ETicketResponse> tickets = eTicketRepository.findByBookingId(booking.getId()).stream()
            .map(ticket -> {
                ETicketResponse raw = eTicketMapper.toResponse(ticket);
                return new ETicketResponse(
                    booking.getBookingRef() + ":" + ticket.getBookingItemId(),
                    ticket.getBookingItemId(),
                    raw.qrCodeData(),
                    raw.pdfUrl(),
                    raw.status()
                );
            })
            .toList();

        return new TicketsByBookingResponse(bookingRef, tickets);
    }
}

package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.api.dto.response.ETicketResponse;
import com.eventplatform.paymentsticketing.api.dto.response.TicketsByBookingResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.ETicket;
import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;
import com.eventplatform.paymentsticketing.mapper.ETicketMapper;
import com.eventplatform.paymentsticketing.repository.ETicketRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ETicketServiceTest {

    @Mock
    private BookingService bookingService;
    @Mock
    private ETicketRepository eTicketRepository;
    @Mock
    private ETicketMapper eTicketMapper;

    private ETicketService eTicketService;

    @BeforeEach
    void setUp() {
        eTicketService = new ETicketService(bookingService, eTicketRepository, eTicketMapper);
    }

    @Test
    void should_return_tickets_for_owned_booking_with_expected_ticket_code_format() {
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);

        ETicket ticket = new ETicket(91L, 501L, "encodedQr", "/tickets/BK-20260304-001/501.pdf");
        when(bookingService.getOwnedBooking(31L, "BK-20260304-001")).thenReturn(booking);
        when(eTicketRepository.findByBookingId(91L)).thenReturn(List.of(ticket));
        when(eTicketMapper.toResponse(ticket)).thenReturn(new ETicketResponse(null, 501L, "encodedQr", "/tickets/BK-20260304-001/501.pdf", ETicketStatus.ACTIVE));

        TicketsByBookingResponse response = eTicketService.getTickets(31L, "BK-20260304-001");

        assertThat(response.bookingRef()).isEqualTo("BK-20260304-001");
        assertThat(response.tickets()).hasSize(1);
        assertThat(response.tickets().get(0).ticketCode()).isEqualTo("BK-20260304-001:501");
    }
}

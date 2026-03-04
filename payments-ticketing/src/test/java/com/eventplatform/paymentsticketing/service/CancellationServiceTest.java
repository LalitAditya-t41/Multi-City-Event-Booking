package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.api.dto.response.CancellationResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.BookingItem;
import com.eventplatform.paymentsticketing.domain.CancellationRequest;
import com.eventplatform.paymentsticketing.domain.ETicket;
import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.BookingItemStatus;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.CancellationRequestStatus;
import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;
import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.exception.CancellationNotAllowedException;
import com.eventplatform.paymentsticketing.exception.RefundFailedException;
import com.eventplatform.paymentsticketing.repository.BookingItemRepository;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.CancellationRequestRepository;
import com.eventplatform.paymentsticketing.repository.ETicketRepository;
import com.eventplatform.paymentsticketing.repository.RefundRepository;
import com.eventplatform.shared.common.event.published.BookingCancelledEvent;
import com.eventplatform.shared.stripe.dto.StripeRefundRequest;
import com.eventplatform.shared.stripe.dto.StripeRefundResponse;
import com.eventplatform.shared.stripe.exception.StripeIntegrationException;
import com.eventplatform.shared.stripe.service.StripeRefundService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingItemRepository bookingItemRepository;
    @Mock
    private ETicketRepository eTicketRepository;
    @Mock
    private RefundRepository refundRepository;
    @Mock
    private CancellationRequestRepository cancellationRequestRepository;
    @Mock
    private StripeRefundService stripeRefundService;
    @Mock
    private AfterCommitEventPublisher afterCommitEventPublisher;

    @Test
    void should_cancel_with_full_refund_and_finalise_when_stripe_refund_succeeds_immediately() throws Exception {
        Instant slotStart = Instant.now().plusSeconds(72 * 3600);
        try (SlotTimingServer server = startSlotTimingServer(21L, slotStart)) {
            CancellationService service = createService(server.baseUrl(), 24, 0);
            Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
            when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));

            CancellationRequest cancellationRequest = new CancellationRequest(91L, 31L, RefundReason.REQUESTED_BY_CUSTOMER);
            when(cancellationRequestRepository.save(any(CancellationRequest.class))).thenReturn(cancellationRequest);

            when(stripeRefundService.createRefund(any(StripeRefundRequest.class)))
                .thenReturn(new StripeRefundResponse("re_123", "succeeded", 150000L, "inr"));

            Refund savedRefund = new Refund(91L, "re_123", 150000L, "inr", RefundReason.REQUESTED_BY_CUSTOMER, RefundStatus.SUCCEEDED);
            when(refundRepository.save(any(Refund.class))).thenReturn(savedRefund);

            BookingItem bookingItem = new BookingItem(91L, 101L, null, "TC-1", 150000L, "inr");
            ETicket ticket = new ETicket(91L, 501L, "qr", "/tickets/BK-20260304-001/501.pdf");
            when(bookingItemRepository.findByBookingId(91L)).thenReturn(List.of(bookingItem));
            when(eTicketRepository.findByBookingId(91L)).thenReturn(List.of(ticket));
            when(refundRepository.findByBookingId(91L)).thenReturn(Optional.of(savedRefund));
            when(cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(91L)).thenReturn(Optional.of(cancellationRequest));
            when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));

            CancellationResponse response = service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER);

            assertThat(response.refund().amount()).isEqualTo(150000L);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(bookingItem.getStatus()).isEqualTo(BookingItemStatus.CANCELLED);
            assertThat(ticket.getStatus()).isEqualTo(ETicketStatus.VOIDED);

            ArgumentCaptor<BookingCancelledEvent> eventCaptor = ArgumentCaptor.forClass(BookingCancelledEvent.class);
            verify(afterCommitEventPublisher).publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().seatIds()).containsExactly(101L);
        }
    }

    @Test
    void should_deduct_fee_percent_from_refund_amount_when_partial_refund_is_configured() throws Exception {
        Instant slotStart = Instant.now().plusSeconds(72 * 3600);
        try (SlotTimingServer server = startSlotTimingServer(21L, slotStart)) {
            CancellationService service = createService(server.baseUrl(), 24, 10);
            Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
            when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));
            when(cancellationRequestRepository.save(any(CancellationRequest.class)))
                .thenReturn(new CancellationRequest(91L, 31L, RefundReason.REQUESTED_BY_CUSTOMER));
            when(stripeRefundService.createRefund(any(StripeRefundRequest.class)))
                .thenReturn(new StripeRefundResponse("re_123", "pending", 135000L, "inr"));
            when(refundRepository.save(any(Refund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER);

            ArgumentCaptor<StripeRefundRequest> requestCaptor = ArgumentCaptor.forClass(StripeRefundRequest.class);
            verify(stripeRefundService).createRefund(requestCaptor.capture());
            assertThat(requestCaptor.getValue().amount()).isEqualTo(135000L);
        }
    }

    @Test
    void should_keep_booking_cancellation_pending_when_refund_is_async_pending() throws Exception {
        Instant slotStart = Instant.now().plusSeconds(72 * 3600);
        try (SlotTimingServer server = startSlotTimingServer(21L, slotStart)) {
            CancellationService service = createService(server.baseUrl(), 24, 0);
            Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
            when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));
            when(cancellationRequestRepository.save(any(CancellationRequest.class)))
                .thenReturn(new CancellationRequest(91L, 31L, RefundReason.REQUESTED_BY_CUSTOMER));
            when(stripeRefundService.createRefund(any(StripeRefundRequest.class)))
                .thenReturn(new StripeRefundResponse("re_123", "pending", 150000L, "inr"));
            when(refundRepository.save(any(Refund.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            CancellationResponse response = service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER);

            assertThat(response.status()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLATION_PENDING);
            verify(afterCommitEventPublisher, never()).publish(any());
        }
    }

    @Test
    void should_throw_when_cancellation_window_has_closed() throws Exception {
        Instant slotStart = Instant.now().plusSeconds(2 * 3600);
        try (SlotTimingServer server = startSlotTimingServer(21L, slotStart)) {
            CancellationService service = createService(server.baseUrl(), 24, 0);
            Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
            when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));

            assertThatThrownBy(() -> service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER))
                .isInstanceOf(CancellationNotAllowedException.class);

            verify(stripeRefundService, never()).createRefund(any());
        }
    }

    @Test
    void should_throw_when_cancelling_booking_in_pending_state() {
        CancellationService service = createService("http://localhost:9", 24, 0);
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER))
            .isInstanceOf(CancellationNotAllowedException.class);
    }

    @Test
    void should_throw_when_cancelling_booking_already_cancelled() {
        CancellationService service = createService("http://localhost:9", 24, 0);
        Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
        booking.markCancellationPending();
        booking.cancel();
        when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER))
            .isInstanceOf(CancellationNotAllowedException.class);
    }

    @Test
    void should_throw_booking_not_found_when_user_is_not_owner() {
        CancellationService service = createService("http://localhost:9", 24, 0);
        Booking booking = confirmedBooking(91L, "BK-20260304-001", 77L, 11L, 21L, 150000L);
        when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER))
            .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    void should_revert_booking_to_confirmed_when_stripe_refund_fails() throws Exception {
        Instant slotStart = Instant.now().plusSeconds(72 * 3600);
        try (SlotTimingServer server = startSlotTimingServer(21L, slotStart)) {
            CancellationService service = createService(server.baseUrl(), 24, 0);
            Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
            when(bookingRepository.findByBookingRef("BK-20260304-001")).thenReturn(Optional.of(booking));

            when(cancellationRequestRepository.save(any(CancellationRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
            when(stripeRefundService.createRefund(any(StripeRefundRequest.class)))
                .thenThrow(new StripeIntegrationException("stripe down"));

            assertThatThrownBy(() -> service.cancel(31L, "BK-20260304-001", RefundReason.REQUESTED_BY_CUSTOMER))
                .isInstanceOf(RefundFailedException.class);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            ArgumentCaptor<CancellationRequest> requestCaptor = ArgumentCaptor.forClass(CancellationRequest.class);
            verify(cancellationRequestRepository).save(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getStatus()).isEqualTo(CancellationRequestStatus.REJECTED);
        }
    }

    @Test
    void should_finalise_after_refund_by_voiding_tickets_and_publishing_booking_cancelled_event() {
        CancellationService service = createService("http://localhost:9", 24, 0);
        Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
        booking.markCancellationPending();

        BookingItem bookingItem = new BookingItem(91L, 101L, null, "TC-1", 150000L, "inr");
        ETicket ticket = new ETicket(91L, 501L, "qr", "/tickets/BK-20260304-001/501.pdf");
        Refund refund = new Refund(91L, "re_123", 150000L, "inr", RefundReason.REQUESTED_BY_CUSTOMER, RefundStatus.PENDING);
        CancellationRequest request = new CancellationRequest(91L, 31L, RefundReason.REQUESTED_BY_CUSTOMER);

        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(bookingItemRepository.findByBookingId(91L)).thenReturn(List.of(bookingItem));
        when(eTicketRepository.findByBookingId(91L)).thenReturn(List.of(ticket));
        when(refundRepository.findByBookingId(91L)).thenReturn(Optional.of(refund));
        when(cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(91L)).thenReturn(Optional.of(request));

        service.finaliseAfterRefund(91L);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookingItem.getStatus()).isEqualTo(BookingItemStatus.CANCELLED);
        assertThat(ticket.getStatus()).isEqualTo(ETicketStatus.VOIDED);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(request.getStatus()).isEqualTo(CancellationRequestStatus.APPROVED);
        verify(afterCommitEventPublisher).publish(any(BookingCancelledEvent.class));
    }

    @Test
    void should_do_nothing_when_finalise_after_refund_called_for_already_cancelled_booking() {
        CancellationService service = createService("http://localhost:9", 24, 0);
        Booking booking = confirmedBooking(91L, "BK-20260304-001", 31L, 11L, 21L, 150000L);
        booking.markCancellationPending();
        booking.cancel();

        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));

        service.finaliseAfterRefund(91L);

        verify(bookingItemRepository, never()).findByBookingId(any());
        verify(eTicketRepository, never()).findByBookingId(any());
        verify(afterCommitEventPublisher, never()).publish(any());
    }

    private CancellationService createService(String baseUrl, int windowHours, int feePercent) {
        return new CancellationService(
            bookingRepository,
            bookingItemRepository,
            eTicketRepository,
            refundRepository,
            cancellationRequestRepository,
            stripeRefundService,
            afterCommitEventPublisher,
            baseUrl,
            windowHours,
            feePercent
        );
    }

    private Booking confirmedBooking(Long id, String ref, Long userId, Long cartId, Long slotId, Long totalAmount) {
        Booking booking = new Booking(ref, cartId, userId, 44L, slotId, totalAmount, "inr");
        ReflectionTestUtils.setField(booking, "id", id);
        booking.confirm("pi_123", "ch_123");
        return booking;
    }

    private SlotTimingServer startSlotTimingServer(Long slotId, Instant startTime) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<String> bodyRef = new AtomicReference<>();
        server.createContext("/internal/scheduling/slots/" + slotId + "/timing", exchange -> {
            String body = "{\"slotId\":" + slotId + ",\"startTime\":\"" + startTime + "\"}";
            bodyRef.set(body);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return new SlotTimingServer(server, "http://localhost:" + server.getAddress().getPort());
    }

    private record SlotTimingServer(HttpServer server, String baseUrl) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }
}

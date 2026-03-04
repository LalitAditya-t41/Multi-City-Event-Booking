package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CheckoutInitResponse;
import com.eventplatform.paymentsticketing.api.dto.response.ETicketResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.BookingItem;
import com.eventplatform.paymentsticketing.domain.ETicket;
import com.eventplatform.paymentsticketing.domain.Payment;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.ETicketStatus;
import com.eventplatform.paymentsticketing.domain.enums.PaymentStatus;
import com.eventplatform.paymentsticketing.exception.CartItemsFetchException;
import com.eventplatform.paymentsticketing.exception.PaymentNotConfirmedException;
import com.eventplatform.paymentsticketing.mapper.BookingMapper;
import com.eventplatform.paymentsticketing.mapper.ETicketMapper;
import com.eventplatform.paymentsticketing.repository.BookingItemRepository;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.ETicketRepository;
import com.eventplatform.paymentsticketing.repository.PaymentRepository;
import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.dto.SlotSummaryDto;
import com.eventplatform.shared.common.enums.SeatingMode;
import com.eventplatform.shared.common.event.published.BookingConfirmedEvent;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import com.eventplatform.shared.common.event.published.PaymentFailedEvent;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentResponse;
import com.eventplatform.shared.stripe.service.StripePaymentService;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BookingItemRepository bookingItemRepository;
    @Mock
    private ETicketRepository eTicketRepository;
    @Mock
    private StripePaymentService stripePaymentService;
    @Mock
    private BookingMapper bookingMapper;
    @Mock
    private ETicketMapper eTicketMapper;
    @Mock
    private AfterCommitEventPublisher afterCommitEventPublisher;
    @Mock
    private BookingRefGenerator bookingRefGenerator;
    @Mock
    private SlotSummaryReader slotSummaryReader;
    @Mock
    private CartSnapshotReader cartSnapshotReader;

    private PaymentService paymentService;
    private HttpServer slotTimingServer;
    private String slotTimingBaseUrl;

    @BeforeEach
    void setUp() throws Exception {
        slotTimingServer = HttpServer.create(new InetSocketAddress(0), 0);
        slotTimingServer.createContext("/internal/scheduling/slots/21/timing", exchange -> {
            Instant startTime = Instant.now().plus(72, ChronoUnit.HOURS);
            String body = "{\"slotId\":21,\"startTime\":\"" + startTime + "\"}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        slotTimingServer.start();
        slotTimingBaseUrl = "http://localhost:" + slotTimingServer.getAddress().getPort();

        paymentService = new PaymentService(
            bookingRepository,
            paymentRepository,
            bookingItemRepository,
            eTicketRepository,
            stripePaymentService,
            bookingMapper,
            eTicketMapper,
            afterCommitEventPublisher,
            bookingRefGenerator,
            slotSummaryReader,
            cartSnapshotReader,
            slotTimingBaseUrl
        );
    }

    @AfterEach
    void tearDown() {
        if (slotTimingServer != null) {
            slotTimingServer.stop(0);
        }
    }

    @Test
    void should_create_checkout_when_cart_is_new() {
        CartAssembledEvent event = new CartAssembledEvent(11L, 21L, 31L, 1L, "ev_1", null, 150000L, "inr", "u@test.com");
        when(bookingRepository.findByCartId(11L)).thenReturn(Optional.empty());
        when(bookingRefGenerator.nextRef()).thenReturn("BK-20260304-001");
        when(slotSummaryReader.getSlotSummary(21L)).thenReturn(new SlotSummaryDto(21L, "ACTIVE", "ev_1", SeatingMode.RESERVED, 1L, 2L, 3L, null));

        Booking savedBooking = new Booking("BK-20260304-001", 11L, 31L, 21L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(savedBooking, "id", 91L);
        when(bookingRepository.save(any(Booking.class))).thenReturn(savedBooking);
        when(stripePaymentService.createPaymentIntent(any())).thenReturn(new StripePaymentIntentResponse("pi_123", "secret_123", "requires_payment_method", 150000L, "inr", null));

        CheckoutInitResponse response = paymentService.createCheckout(event);

        assertThat(response.paymentIntentId()).isEqualTo("pi_123");
        assertThat(response.clientSecret()).isEqualTo("secret_123");
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void should_return_existing_payment_intent_without_stripe_create_when_checkout_already_exists() {
        CartAssembledEvent event = new CartAssembledEvent(11L, 21L, 31L, 1L, "ev_1", null, 150000L, "inr", "u@test.com");
        Booking existingBooking = new Booking("BK-20260304-001", 11L, 31L, 21L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(existingBooking, "id", 91L);
        Payment existingPayment = new Payment(91L, "pi_existing", 150000L, "inr");

        when(bookingRepository.findByCartId(11L)).thenReturn(Optional.of(existingBooking));
        when(paymentRepository.findByBookingId(91L)).thenReturn(Optional.of(existingPayment));
        when(stripePaymentService.getPaymentIntent("pi_existing"))
            .thenReturn(new StripePaymentIntentResponse("pi_existing", "secret_existing", "requires_payment_method", 150000L, "inr", null));

        CheckoutInitResponse response = paymentService.createCheckout(event);

        assertThat(response.paymentIntentId()).isEqualTo("pi_existing");
        verify(stripePaymentService, never()).createPaymentIntent(any());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void should_throw_and_not_save_payment_when_create_checkout_stripe_call_fails() {
        CartAssembledEvent event = new CartAssembledEvent(11L, 21L, 31L, 1L, "ev_1", null, 150000L, "inr", "u@test.com");
        when(bookingRepository.findByCartId(11L)).thenReturn(Optional.empty());
        when(bookingRefGenerator.nextRef()).thenReturn("BK-20260304-001");
        when(slotSummaryReader.getSlotSummary(21L)).thenReturn(new SlotSummaryDto(21L, "ACTIVE", "ev_1", SeatingMode.RESERVED, 1L, 2L, 3L, null));

        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 21L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(stripePaymentService.createPaymentIntent(any())).thenThrow(new RuntimeException("stripe down"));

        assertThatThrownBy(() -> paymentService.createCheckout(event)).isInstanceOf(RuntimeException.class);

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void should_confirm_payment_and_create_items_and_tickets_when_stripe_status_succeeded() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(stripePaymentService.getPaymentIntent("pi_123"))
            .thenReturn(new StripePaymentIntentResponse("pi_123", "secret", "succeeded", 150000L, "inr", "ch_123"));

        CartItemSnapshotDto cartItem = new CartItemSnapshotDto(1L, 101L, null, "TC-1", 75000L, "inr", 1);
        when(cartSnapshotReader.getCartItems(11L)).thenReturn(List.of(cartItem));

        BookingItem savedItem = new BookingItem(91L, 101L, null, "TC-1", 75000L, "inr");
        ReflectionTestUtils.setField(savedItem, "id", 501L);
        when(bookingItemRepository.save(any(BookingItem.class))).thenReturn(savedItem);

        ETicket savedTicket = new ETicket(91L, 501L, "encoded-qr", "/tickets/BK-20260304-001/501.pdf");
        when(eTicketRepository.save(any(ETicket.class))).thenReturn(savedTicket);
        when(eTicketRepository.findByBookingId(91L)).thenReturn(List.of(savedTicket));
        when(bookingItemRepository.findByBookingId(91L)).thenReturn(List.of(savedItem));

        when(bookingMapper.toResponse(booking)).thenReturn(new BookingResponse(
            booking.getBookingRef(),
            BookingStatus.CONFIRMED,
            booking.getSlotId(),
            booking.getTotalAmount(),
            booking.getCurrency(),
            "pi_123",
            List.of(),
            Instant.parse("2026-03-04T10:00:00Z")
        ));
        when(eTicketMapper.toResponse(savedTicket)).thenReturn(new ETicketResponse(
            null,
            501L,
            savedTicket.getQrCodeData(),
            savedTicket.getPdfUrl(),
            ETicketStatus.ACTIVE
        ));

        BookingResponse response = paymentService.confirmPayment(31L, "pi_123");

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).eTicket().ticketCode()).isEqualTo("BK-20260304-001:501");

        ArgumentCaptor<BookingConfirmedEvent> eventCaptor = ArgumentCaptor.forClass(BookingConfirmedEvent.class);
        verify(afterCommitEventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().seatIds()).containsExactly(101L);
    }

    @Test
    void should_return_existing_booking_when_payment_already_confirmed() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        payment.markSuccess("ch_123");

        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);
        booking.confirm("pi_123", "ch_123");

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(eTicketRepository.findByBookingId(91L)).thenReturn(List.of());
        when(bookingItemRepository.findByBookingId(91L)).thenReturn(List.of());
        when(bookingMapper.toResponse(booking)).thenReturn(new BookingResponse(
            booking.getBookingRef(),
            booking.getStatus(),
            booking.getSlotId(),
            booking.getTotalAmount(),
            booking.getCurrency(),
            booking.getStripePaymentIntentId(),
            List.of(),
            Instant.parse("2026-03-04T10:00:00Z")
        ));

        BookingResponse response = paymentService.confirmPayment(31L, "pi_123");

        assertThat(response.bookingRef()).isEqualTo("BK-20260304-001");
        verify(stripePaymentService, never()).getPaymentIntent(any());
        verify(bookingItemRepository, never()).save(any(BookingItem.class));
        verify(eTicketRepository, never()).save(any(ETicket.class));
        verify(afterCommitEventPublisher, never()).publish(any());
    }

    @Test
    void should_throw_payment_not_confirmed_when_stripe_status_requires_action() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(stripePaymentService.getPaymentIntent("pi_123"))
            .thenReturn(new StripePaymentIntentResponse("pi_123", "secret", "requires_action", 150000L, "inr", null));

        assertThatThrownBy(() -> paymentService.confirmPayment(31L, "pi_123"))
            .isInstanceOf(PaymentNotConfirmedException.class);
    }

    @Test
    void should_throw_and_leave_pending_state_when_confirm_payment_stripe_retrieve_times_out() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(stripePaymentService.getPaymentIntent("pi_123")).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> paymentService.confirmPayment(31L, "pi_123"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("timeout");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void should_throw_and_not_publish_event_when_cart_items_fetch_returns_empty() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(stripePaymentService.getPaymentIntent("pi_123"))
            .thenReturn(new StripePaymentIntentResponse("pi_123", "secret", "succeeded", 150000L, "inr", "ch_123"));
        when(cartSnapshotReader.getCartItems(11L)).thenReturn(List.of());

        assertThatThrownBy(() -> paymentService.confirmPayment(31L, "pi_123"))
            .isInstanceOf(CartItemsFetchException.class);

        verify(bookingItemRepository, never()).save(any(BookingItem.class));
        verify(eTicketRepository, never()).save(any(ETicket.class));
        verify(afterCommitEventPublisher, never()).publish(any());
    }

    @Test
    void should_set_payment_failed_and_booking_cancelled_and_publish_event_when_handle_failure_called() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        Booking booking = new Booking("BK-20260304-001", 11L, 31L, 44L, 21L, 150000L, "inr");
        ReflectionTestUtils.setField(booking, "id", 91L);
        BookingItem seatItem = new BookingItem(91L, 101L, null, "TC-1", 75000L, "inr");
        BookingItem gaItem = new BookingItem(91L, null, 201L, "TC-2", 75000L, "inr");

        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));
        when(bookingRepository.findById(91L)).thenReturn(Optional.of(booking));
        when(bookingItemRepository.findByBookingId(91L)).thenReturn(List.of(seatItem, gaItem));

        paymentService.handleFailure("pi_123", "card_declined", "declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureCode()).isEqualTo("card_declined");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);

        ArgumentCaptor<PaymentFailedEvent> eventCaptor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(afterCommitEventPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().seatIds()).containsExactly(101L);
    }

    @Test
    void should_do_nothing_when_handle_failure_called_for_already_failed_payment() {
        Payment payment = new Payment(91L, "pi_123", 150000L, "inr");
        payment.markFailed("declined", "failed");
        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));

        paymentService.handleFailure("pi_123", "declined", "failed");

        verify(bookingRepository, never()).findById(any());
        verify(afterCommitEventPublisher, never()).publish(any());
    }
}

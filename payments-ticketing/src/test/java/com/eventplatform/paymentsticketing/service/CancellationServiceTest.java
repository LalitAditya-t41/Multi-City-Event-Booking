package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.api.dto.response.CancelItemsResponse;
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
import com.eventplatform.paymentsticketing.exception.DuplicateItemCancellationException;
import com.eventplatform.paymentsticketing.exception.InvalidCancelItemsRequestException;
import com.eventplatform.paymentsticketing.exception.RefundFailedException;
import com.eventplatform.paymentsticketing.repository.BookingItemRepository;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.CancellationRequestRepository;
import com.eventplatform.paymentsticketing.repository.ETicketRepository;
import com.eventplatform.paymentsticketing.repository.RefundRepository;
import com.eventplatform.shared.common.event.published.BookingCancelledEvent;
import com.eventplatform.shared.stripe.dto.StripeRefundResponse;
import com.eventplatform.shared.stripe.exception.StripeIntegrationException;
import com.eventplatform.shared.stripe.service.StripeRefundService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CancellationServiceTest {

  @Mock private BookingRepository bookingRepository;
  @Mock private BookingItemRepository bookingItemRepository;
  @Mock private ETicketRepository eTicketRepository;
  @Mock private RefundRepository refundRepository;
  @Mock private CancellationRequestRepository cancellationRequestRepository;
  @Mock private StripeRefundService stripeRefundService;
  @Mock private AfterCommitEventPublisher afterCommitEventPublisher;
  @Mock private CancellationPolicyService cancellationPolicyService;

  private CancellationService cancellationService;

  @BeforeEach
  void setUp() {
    cancellationService =
        new CancellationService(
            bookingRepository,
            bookingItemRepository,
            eTicketRepository,
            refundRepository,
            cancellationRequestRepository,
            stripeRefundService,
            afterCommitEventPublisher,
            cancellationPolicyService);
  }

  @Test
  void
      should_cancel_selected_items_and_keep_booking_confirmed_when_partial_item_cancel_requested() {
    // Arrange
    Booking booking = confirmedBooking(91L, "BK-20260305-001", 31L, 11L, 200000L);
    BookingItem firstItem = bookingItem(101L, 91L, 301L, 100000L);
    BookingItem secondItem = bookingItem(102L, 91L, 302L, 100000L);
    ETicket firstTicket = ticket(501L, 91L, 101L);

    when(bookingRepository.findByBookingRef("BK-20260305-001")).thenReturn(Optional.of(booking));
    when(bookingItemRepository.findByBookingId(91L)).thenReturn(List.of(firstItem, secondItem));
    when(cancellationRequestRepository.existsByBookingItemIdAndStatus(
            101L, CancellationRequestStatus.PENDING))
        .thenReturn(false);
    when(cancellationPolicyService.calculateRefund(booking, 100000L))
        .thenReturn(new RefundCalculationResult(50, 50000L, "GE_24_HOURS"));
    when(stripeRefundService.createRefund(any()))
        .thenReturn(new StripeRefundResponse("re_1", "succeeded", 50000L, "inr"));
    when(refundRepository.save(any(Refund.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(cancellationRequestRepository.findByBookingIdAndStatus(
            91L, CancellationRequestStatus.PENDING))
        .thenReturn(
            List.of(new CancellationRequest(91L, 101L, 31L, RefundReason.REQUESTED_BY_CUSTOMER)));
    when(eTicketRepository.findByBookingItemId(101L)).thenReturn(Optional.of(firstTicket));

    // Act
    CancelItemsResponse response =
        cancellationService.cancelItems(
            31L, "BK-20260305-001", List.of(101L), RefundReason.REQUESTED_BY_CUSTOMER);

    // Assert
    assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
    assertThat(response.cancelledItemIds()).containsExactly(101L);
    assertThat(response.refund().percent()).isEqualTo(50);
    assertThat(response.refund().amount()).isEqualTo(50000L);
    assertThat(firstItem.getStatus()).isEqualTo(BookingItemStatus.CANCELLED);
    assertThat(secondItem.getStatus()).isEqualTo(BookingItemStatus.ACTIVE);
    assertThat(firstTicket.getStatus()).isEqualTo(ETicketStatus.VOIDED);
    assertThat(booking.getTotalAmount()).isEqualTo(100000L);

    ArgumentCaptor<BookingCancelledEvent> eventCaptor =
        ArgumentCaptor.forClass(BookingCancelledEvent.class);
    verify(afterCommitEventPublisher).publish(eventCaptor.capture());
    assertThat(eventCaptor.getValue().seatIds()).containsExactly(301L);
  }

  @Test
  void should_cancel_booking_with_zero_refund_and_skip_stripe_when_policy_returns_zero() {
    // Arrange
    Booking booking = confirmedBooking(92L, "BK-20260305-002", 31L, 12L, 150000L);
    BookingItem firstItem = bookingItem(201L, 92L, 401L, 75000L);
    BookingItem secondItem = bookingItem(202L, 92L, 402L, 75000L);
    ETicket firstTicket = ticket(601L, 92L, 201L);
    ETicket secondTicket = ticket(602L, 92L, 202L);

    when(bookingRepository.findByBookingRef("BK-20260305-002")).thenReturn(Optional.of(booking));
    when(bookingItemRepository.findByBookingId(92L)).thenReturn(List.of(firstItem, secondItem));
    when(cancellationRequestRepository.existsByBookingItemIdAndStatus(any(), any()))
        .thenReturn(false);
    when(cancellationPolicyService.calculateRefund(booking, 150000L))
        .thenReturn(new RefundCalculationResult(0, 0L, "DEFAULT_TIER"));
    when(refundRepository.save(any(Refund.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(cancellationRequestRepository.findByBookingIdAndStatus(
            92L, CancellationRequestStatus.PENDING))
        .thenReturn(
            List.of(
                new CancellationRequest(92L, 201L, 31L, RefundReason.REQUESTED_BY_CUSTOMER),
                new CancellationRequest(92L, 202L, 31L, RefundReason.REQUESTED_BY_CUSTOMER)));
    when(eTicketRepository.findByBookingItemId(201L)).thenReturn(Optional.of(firstTicket));
    when(eTicketRepository.findByBookingItemId(202L)).thenReturn(Optional.of(secondTicket));

    // Act
    CancelItemsResponse response =
        cancellationService.cancelItems(
            31L, "BK-20260305-002", List.of(201L, 202L), RefundReason.REQUESTED_BY_CUSTOMER);

    // Assert
    assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(response.refund().amount()).isEqualTo(0L);
    assertThat(response.refund().status()).isEqualTo(RefundStatus.SUCCEEDED.name());
    verify(stripeRefundService, never()).createRefund(any());
  }

  @Test
  void should_throw_invalid_cancel_items_request_when_item_does_not_belong_to_booking() {
    // Arrange
    Booking booking = confirmedBooking(93L, "BK-20260305-003", 31L, 13L, 50000L);
    BookingItem activeItem = bookingItem(301L, 93L, 501L, 50000L);

    when(bookingRepository.findByBookingRef("BK-20260305-003")).thenReturn(Optional.of(booking));
    when(bookingItemRepository.findByBookingId(93L)).thenReturn(List.of(activeItem));

    // Act + Assert
    assertThatThrownBy(
            () ->
                cancellationService.cancelItems(
                    31L, "BK-20260305-003", List.of(999L), RefundReason.REQUESTED_BY_CUSTOMER))
        .isInstanceOf(InvalidCancelItemsRequestException.class);
  }

  @Test
  void should_throw_duplicate_item_cancellation_when_pending_request_exists_for_item() {
    // Arrange
    Booking booking = confirmedBooking(94L, "BK-20260305-004", 31L, 14L, 80000L);
    BookingItem activeItem = bookingItem(401L, 94L, 601L, 80000L);

    when(bookingRepository.findByBookingRef("BK-20260305-004")).thenReturn(Optional.of(booking));
    when(bookingItemRepository.findByBookingId(94L)).thenReturn(List.of(activeItem));
    when(cancellationRequestRepository.existsByBookingItemIdAndStatus(
            401L, CancellationRequestStatus.PENDING))
        .thenReturn(true);

    // Act + Assert
    assertThatThrownBy(
            () ->
                cancellationService.cancelItems(
                    31L, "BK-20260305-004", List.of(401L), RefundReason.REQUESTED_BY_CUSTOMER))
        .isInstanceOf(DuplicateItemCancellationException.class);
  }

  @Test
  void should_revert_booking_to_confirmed_when_stripe_refund_fails_during_item_cancel() {
    // Arrange
    Booking booking = confirmedBooking(95L, "BK-20260305-005", 31L, 15L, 100000L);
    BookingItem activeItem = bookingItem(501L, 95L, 701L, 100000L);

    when(bookingRepository.findByBookingRef("BK-20260305-005")).thenReturn(Optional.of(booking));
    when(bookingItemRepository.findByBookingId(95L)).thenReturn(List.of(activeItem));
    when(cancellationRequestRepository.existsByBookingItemIdAndStatus(
            501L, CancellationRequestStatus.PENDING))
        .thenReturn(false);
    when(cancellationPolicyService.calculateRefund(booking, 100000L))
        .thenReturn(new RefundCalculationResult(100, 100000L, "GE_72_HOURS"));
    when(stripeRefundService.createRefund(any()))
        .thenThrow(new StripeIntegrationException("stripe-down"));

    // Act + Assert
    assertThatThrownBy(
            () ->
                cancellationService.cancelItems(
                    31L, "BK-20260305-005", List.of(501L), RefundReason.REQUESTED_BY_CUSTOMER))
        .isInstanceOf(RefundFailedException.class);

    assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
  }

  @Test
  void should_cancel_full_booking_using_policy_driven_refund_amount() {
    // Arrange
    Booking booking = confirmedBooking(96L, "BK-20260305-006", 31L, 16L, 150000L);
    BookingItem firstItem = bookingItem(601L, 96L, 801L, 100000L);
    BookingItem secondItem = bookingItem(602L, 96L, 802L, 50000L);
    ETicket firstTicket = ticket(701L, 96L, 601L);
    ETicket secondTicket = ticket(702L, 96L, 602L);
    CancellationRequest request =
        new CancellationRequest(96L, 31L, RefundReason.REQUESTED_BY_CUSTOMER);

    when(bookingRepository.findByBookingRef("BK-20260305-006")).thenReturn(Optional.of(booking));
    when(bookingItemRepository.findByBookingId(96L)).thenReturn(List.of(firstItem, secondItem));
    when(cancellationPolicyService.calculateRefund(booking, 150000L))
        .thenReturn(new RefundCalculationResult(40, 60000L, "GE_24_HOURS"));
    when(stripeRefundService.createRefund(any()))
        .thenReturn(new StripeRefundResponse("re_full", "succeeded", 60000L, "inr"));
    when(refundRepository.save(any(Refund.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(96L))
        .thenReturn(Optional.of(request));
    when(eTicketRepository.findByBookingItemId(601L)).thenReturn(Optional.of(firstTicket));
    when(eTicketRepository.findByBookingItemId(602L)).thenReturn(Optional.of(secondTicket));

    // Act
    CancellationResponse response =
        cancellationService.cancel(31L, "BK-20260305-006", RefundReason.REQUESTED_BY_CUSTOMER);

    // Assert
    assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
    assertThat(response.refund().amount()).isEqualTo(60000L);
    assertThat(firstItem.getStatus()).isEqualTo(BookingItemStatus.CANCELLED);
    assertThat(secondItem.getStatus()).isEqualTo(BookingItemStatus.CANCELLED);
    assertThat(firstTicket.getStatus()).isEqualTo(ETicketStatus.VOIDED);
    assertThat(secondTicket.getStatus()).isEqualTo(ETicketStatus.VOIDED);
    assertThat(booking.getTotalAmount()).isZero();
  }

  private Booking confirmedBooking(
      Long id, String bookingRef, Long userId, Long cartId, Long totalAmount) {
    Booking booking =
        new Booking(
            bookingRef,
            cartId,
            userId,
            44L,
            21L,
            88L,
            Instant.now().plusSeconds(72 * 3600),
            totalAmount,
            "inr");
    ReflectionTestUtils.setField(booking, "id", id);
    booking.confirm("pi_123", "ch_123");
    return booking;
  }

  private BookingItem bookingItem(Long id, Long bookingId, Long seatId, Long unitPrice) {
    BookingItem item = new BookingItem(bookingId, seatId, null, "TC-1", unitPrice, "inr");
    ReflectionTestUtils.setField(item, "id", id);
    return item;
  }

  private ETicket ticket(Long id, Long bookingId, Long bookingItemId) {
    ETicket ticket = new ETicket(bookingId, bookingItemId, "qr", "/tickets/sample.pdf");
    ReflectionTestUtils.setField(ticket, "id", id);
    return ticket;
  }
}

package com.eventplatform.paymentsticketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.EventCancellationRefundAudit;
import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.EventCancellationAuditStatus;
import com.eventplatform.paymentsticketing.domain.enums.RefundCancellationType;
import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.EventCancellationRefundAuditRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class EventCancellationRefundServiceTest {

  @Mock private BookingRepository bookingRepository;
  @Mock private EventCancellationRefundAuditRepository eventCancellationRefundAuditRepository;
  @Mock private CancellationService cancellationService;
  @Mock private PlatformTransactionManager transactionManager;

  private EventCancellationRefundService eventCancellationRefundService;

  @BeforeEach
  void setUp() {
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
    doNothing().when(transactionManager).commit(status);
    doNothing().when(transactionManager).rollback(status);

    eventCancellationRefundService =
        new EventCancellationRefundService(
            bookingRepository,
            eventCancellationRefundAuditRepository,
            cancellationService,
            transactionManager);
  }

  @Test
  void should_continue_processing_remaining_bookings_when_one_booking_refund_fails() {
    // Arrange
    Booking firstBooking = confirmedBooking(1001L, "BK-1001");
    Booking secondBooking = confirmedBooking(1002L, "BK-1002");

    when(bookingRepository.findBySlotIdAndStatus(77L, BookingStatus.CONFIRMED))
        .thenReturn(List.of(firstBooking, secondBooking));
    when(eventCancellationRefundAuditRepository.findBySlotIdAndBookingId(77L, 1001L))
        .thenReturn(Optional.empty());
    when(eventCancellationRefundAuditRepository.findBySlotIdAndBookingId(77L, 1002L))
        .thenReturn(Optional.empty());
    when(eventCancellationRefundAuditRepository.save(any(EventCancellationRefundAudit.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Refund refund =
        new Refund(
            1001L,
            null,
            100000L,
            "inr",
            RefundReason.REQUESTED_BY_CUSTOMER,
            RefundStatus.SUCCEEDED,
            RefundCancellationType.EVENT_CANCELLED);
    ReflectionTestUtils.setField(refund, "id", 9001L);

    when(cancellationService.cancelForEventCancellation(1001L)).thenReturn(refund);
    when(cancellationService.cancelForEventCancellation(1002L))
        .thenThrow(new RuntimeException("downstream-failure"));

    // Act
    eventCancellationRefundService.processEventRefunds(77L, 88L);

    // Assert
    verify(cancellationService).cancelForEventCancellation(1001L);
    verify(cancellationService).cancelForEventCancellation(1002L);

    ArgumentCaptor<EventCancellationRefundAudit> captor =
        ArgumentCaptor.forClass(EventCancellationRefundAudit.class);
    verify(eventCancellationRefundAuditRepository, org.mockito.Mockito.atLeast(4))
        .save(captor.capture());

    List<EventCancellationRefundAudit> saved = captor.getAllValues();
    assertThat(
            saved.stream()
                .anyMatch(
                    audit ->
                        audit.getBookingId().equals(1001L)
                            && audit.getStatus() == EventCancellationAuditStatus.SUCCEEDED))
        .isTrue();
    assertThat(
            saved.stream()
                .anyMatch(
                    audit ->
                        audit.getBookingId().equals(1002L)
                            && audit.getStatus() == EventCancellationAuditStatus.FAILED))
        .isTrue();
  }

  private Booking confirmedBooking(Long id, String bookingRef) {
    Booking booking =
        new Booking(
            bookingRef,
            11L,
            31L,
            44L,
            77L,
            88L,
            Instant.now().plusSeconds(24 * 3600),
            100000L,
            "inr");
    ReflectionTestUtils.setField(booking, "id", id);
    booking.confirm("pi_123", "ch_123");
    return booking;
  }
}

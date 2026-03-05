package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.EventCancellationRefundAudit;
import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.EventCancellationAuditStatus;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.EventCancellationRefundAuditRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventCancellationRefundService {

  private final BookingRepository bookingRepository;
  private final EventCancellationRefundAuditRepository eventCancellationRefundAuditRepository;
  private final CancellationService cancellationService;
  private final TransactionTemplate transactionTemplate;

  public EventCancellationRefundService(
      BookingRepository bookingRepository,
      EventCancellationRefundAuditRepository eventCancellationRefundAuditRepository,
      CancellationService cancellationService,
      PlatformTransactionManager transactionManager) {
    this.bookingRepository = bookingRepository;
    this.eventCancellationRefundAuditRepository = eventCancellationRefundAuditRepository;
    this.cancellationService = cancellationService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public void processEventRefunds(Long slotId, Long orgId) {
    List<Booking> confirmedBookings =
        bookingRepository.findBySlotIdAndStatus(slotId, BookingStatus.CONFIRMED);

    for (Booking booking : confirmedBookings) {
      ensureAuditRow(slotId, booking.getId());

      try {
        transactionTemplate.executeWithoutResult(
            status -> {
              EventCancellationRefundAudit audit =
                  eventCancellationRefundAuditRepository
                      .findBySlotIdAndBookingId(slotId, booking.getId())
                      .orElseGet(() -> new EventCancellationRefundAudit(slotId, booking.getId()));
              if (audit.getStatus() == EventCancellationAuditStatus.SUCCEEDED) {
                return;
              }
              audit.markPending();
              eventCancellationRefundAuditRepository.save(audit);

              // FR6 stub seam: no dedicated batch Stripe call yet; reuses cancellation
              // orchestration.
              Refund refund = cancellationService.cancelForEventCancellation(booking.getId());
              audit.markSucceeded(refund.getId());
              eventCancellationRefundAuditRepository.save(audit);
            });
      } catch (Exception ex) {
        transactionTemplate.executeWithoutResult(
            status -> {
              EventCancellationRefundAudit audit =
                  eventCancellationRefundAuditRepository
                      .findBySlotIdAndBookingId(slotId, booking.getId())
                      .orElseGet(() -> new EventCancellationRefundAudit(slotId, booking.getId()));
              audit.markFailed(ex.getMessage());
              eventCancellationRefundAuditRepository.save(audit);
            });
      }
    }
  }

  private void ensureAuditRow(Long slotId, Long bookingId) {
    transactionTemplate.executeWithoutResult(
        status -> {
          eventCancellationRefundAuditRepository
              .findBySlotIdAndBookingId(slotId, bookingId)
              .orElseGet(
                  () ->
                      eventCancellationRefundAuditRepository.save(
                          new EventCancellationRefundAudit(slotId, bookingId)));
        });
  }
}

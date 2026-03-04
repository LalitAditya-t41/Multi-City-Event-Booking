package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.api.dto.response.CancellationResponse;
import com.eventplatform.paymentsticketing.api.dto.response.RefundResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.BookingItem;
import com.eventplatform.paymentsticketing.domain.CancellationRequest;
import com.eventplatform.paymentsticketing.domain.ETicket;
import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class CancellationService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final ETicketRepository eTicketRepository;
    private final RefundRepository refundRepository;
    private final CancellationRequestRepository cancellationRequestRepository;
    private final StripeRefundService stripeRefundService;
    private final AfterCommitEventPublisher afterCommitEventPublisher;
    private final RestClient restClient;
    private final int cancellationWindowHours;
    private final int feePercent;

    public CancellationService(
        BookingRepository bookingRepository,
        BookingItemRepository bookingItemRepository,
        ETicketRepository eTicketRepository,
        RefundRepository refundRepository,
        CancellationRequestRepository cancellationRequestRepository,
        StripeRefundService stripeRefundService,
        AfterCommitEventPublisher afterCommitEventPublisher,
        @Value("${app.internal-base-url:http://localhost:8080}") String internalBaseUrl,
        @Value("${payments.cancellation.window-hours:24}") int cancellationWindowHours,
        @Value("${payments.cancellation.fee-percent:0}") int feePercent
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.eTicketRepository = eTicketRepository;
        this.refundRepository = refundRepository;
        this.cancellationRequestRepository = cancellationRequestRepository;
        this.stripeRefundService = stripeRefundService;
        this.afterCommitEventPublisher = afterCommitEventPublisher;
        this.restClient = RestClient.builder().baseUrl(internalBaseUrl).build();
        this.cancellationWindowHours = cancellationWindowHours;
        this.feePercent = feePercent;
    }

    @Transactional
    public CancellationResponse cancel(Long userId, String bookingRef, RefundReason reason) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new BookingNotFoundException(bookingRef));

        if (!Objects.equals(booking.getUserId(), userId)) {
            throw new BookingNotFoundException(bookingRef);
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new CancellationNotAllowedException("Booking is not in CONFIRMED state", Map.of("currentStatus", booking.getStatus().name()));
        }

        SlotTimingResponse slotTiming = fetchSlotTiming(booking.getSlotId());
        Instant windowClosedAt = slotTiming.startTime().minusSeconds(cancellationWindowHours * 3600L);
        if (!Instant.now().isBefore(windowClosedAt)) {
            throw new CancellationNotAllowedException(
                "Cancellation window has closed",
                Map.of("windowClosedAt", windowClosedAt.toString())
            );
        }

        CancellationRequest cancellationRequest = new CancellationRequest(booking.getId(), userId, reason);
        cancellationRequestRepository.save(cancellationRequest);
        booking.markCancellationPending();

        long refundAmount = booking.getTotalAmount() - ((booking.getTotalAmount() * feePercent) / 100);

        try {
            StripeRefundResponse stripeRefund = stripeRefundService.createRefund(new StripeRefundRequest(
                booking.getStripePaymentIntentId(),
                refundAmount,
                reason.name().toLowerCase(),
                "refund-" + booking.getBookingRef()
            ));

            RefundStatus refundStatus = toRefundStatus(stripeRefund.status());
            Refund refund = refundRepository.save(new Refund(
                booking.getId(),
                stripeRefund.refundId(),
                refundAmount,
                booking.getCurrency(),
                reason,
                refundStatus
            ));

            cancellationRequest.approve();
            if (refundStatus == RefundStatus.SUCCEEDED) {
                finaliseAfterRefund(booking.getId());
            }

            return new CancellationResponse(
                booking.getBookingRef(),
                booking.getStatus(),
                new RefundResponse(refund.getStripeRefundId(), refund.getAmount(), refund.getCurrency(), refund.getStatus())
            );
        } catch (StripeIntegrationException ex) {
            booking.revertCancellationPending();
            cancellationRequest.reject();
            throw new RefundFailedException("Unable to process refund at this time. Please contact support.", ex.getMessage());
        }
    }

    @Transactional
    public void finaliseAfterRefund(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }

        booking.cancel();

        List<Long> seatIds = bookingItemRepository.findByBookingId(bookingId).stream()
            .peek(BookingItem::cancel)
            .map(BookingItem::getSeatId)
            .filter(Objects::nonNull)
            .toList();

        eTicketRepository.findByBookingId(bookingId).forEach(ETicket::voidTicket);

        refundRepository.findByBookingId(bookingId).ifPresent(refund -> refund.updateStatus(RefundStatus.SUCCEEDED));
        cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(bookingId).ifPresent(CancellationRequest::approve);

        afterCommitEventPublisher.publish(new BookingCancelledEvent(
            booking.getId(),
            booking.getCartId(),
            seatIds,
            booking.getUserId(),
            "BUYER_CANCEL"
        ));
    }

    @Transactional
    public void handleRefundFailed(String refundId) {
        refundRepository.findByStripeRefundId(refundId).ifPresent(refund -> {
            refund.updateStatus(RefundStatus.FAILED);
            cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(refund.getBookingId())
                .ifPresent(CancellationRequest::reject);
        });
    }

    private SlotTimingResponse fetchSlotTiming(Long slotId) {
        SlotTimingResponse response = restClient.get()
            .uri("/internal/scheduling/slots/{slotId}/timing", slotId)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                throw new CancellationNotAllowedException("Unable to validate cancellation window");
            })
            .body(new ParameterizedTypeReference<>() {
            });
        if (response == null || response.startTime() == null) {
            throw new CancellationNotAllowedException("Unable to validate cancellation window");
        }
        return response;
    }

    private RefundStatus toRefundStatus(String stripeStatus) {
        if ("succeeded".equalsIgnoreCase(stripeStatus)) {
            return RefundStatus.SUCCEEDED;
        }
        if ("failed".equalsIgnoreCase(stripeStatus) || "canceled".equalsIgnoreCase(stripeStatus)) {
            return RefundStatus.FAILED;
        }
        return RefundStatus.PENDING;
    }

    public record SlotTimingResponse(Long slotId, Instant startTime) {
    }
}

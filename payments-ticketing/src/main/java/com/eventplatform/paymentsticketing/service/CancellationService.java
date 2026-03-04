package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.api.dto.response.CancelItemsResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationQuoteResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CancellationResponse;
import com.eventplatform.paymentsticketing.api.dto.response.RefundResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.BookingItem;
import com.eventplatform.paymentsticketing.domain.CancellationRequest;
import com.eventplatform.paymentsticketing.domain.ETicket;
import com.eventplatform.paymentsticketing.domain.Refund;
import com.eventplatform.paymentsticketing.domain.enums.BookingItemStatus;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.CancellationRequestStatus;
import com.eventplatform.paymentsticketing.domain.enums.RefundCancellationType;
import com.eventplatform.paymentsticketing.domain.enums.RefundReason;
import com.eventplatform.paymentsticketing.domain.enums.RefundStatus;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.exception.CancellationNotAllowedException;
import com.eventplatform.paymentsticketing.exception.DuplicateItemCancellationException;
import com.eventplatform.paymentsticketing.exception.InvalidCancelItemsRequestException;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancellationService {

    private final BookingRepository bookingRepository;
    private final BookingItemRepository bookingItemRepository;
    private final ETicketRepository eTicketRepository;
    private final RefundRepository refundRepository;
    private final CancellationRequestRepository cancellationRequestRepository;
    private final StripeRefundService stripeRefundService;
    private final AfterCommitEventPublisher afterCommitEventPublisher;
    private final CancellationPolicyService cancellationPolicyService;

    public CancellationService(
        BookingRepository bookingRepository,
        BookingItemRepository bookingItemRepository,
        ETicketRepository eTicketRepository,
        RefundRepository refundRepository,
        CancellationRequestRepository cancellationRequestRepository,
        StripeRefundService stripeRefundService,
        AfterCommitEventPublisher afterCommitEventPublisher,
        CancellationPolicyService cancellationPolicyService
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.eTicketRepository = eTicketRepository;
        this.refundRepository = refundRepository;
        this.cancellationRequestRepository = cancellationRequestRepository;
        this.stripeRefundService = stripeRefundService;
        this.afterCommitEventPublisher = afterCommitEventPublisher;
        this.cancellationPolicyService = cancellationPolicyService;
    }

    @Transactional(readOnly = true)
    public CancellationQuoteResponse computeQuote(Long userId, String bookingRef, List<Long> requestedBookingItemIds) {
        Booking booking = getOwnedBooking(userId, bookingRef);
        ensureBookingConfirmed(booking);

        List<BookingItem> selectedItems = resolveSelectedActiveItems(booking.getId(), requestedBookingItemIds);
        long requestedAmount = selectedItems.stream().mapToLong(BookingItem::getUnitPrice).sum();

        RefundCalculationResult quote = cancellationPolicyService.calculateRefund(booking, requestedAmount);
        return new CancellationQuoteResponse(
            booking.getBookingRef(),
            quote.tierLabel(),
            quote.refundPercent(),
            requestedAmount,
            quote.refundAmountInSmallestUnit(),
            booking.getCurrency()
        );
    }

    @Transactional
    public CancelItemsResponse cancelItems(Long userId, String bookingRef, List<Long> requestedBookingItemIds, RefundReason reason) {
        Booking booking = getOwnedBooking(userId, bookingRef);
        ensureBookingConfirmed(booking);

        List<BookingItem> allItems = bookingItemRepository.findByBookingId(booking.getId());
        List<BookingItem> activeItems = allItems.stream()
            .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
            .toList();

        if (activeItems.isEmpty()) {
            throw new CancellationNotAllowedException("Booking has no active items to cancel");
        }

        List<BookingItem> selectedItems = resolveSelectedItemsFromActive(activeItems, requestedBookingItemIds);

        for (BookingItem item : selectedItems) {
            if (cancellationRequestRepository.existsByBookingItemIdAndStatus(item.getId(), CancellationRequestStatus.PENDING)) {
                throw new DuplicateItemCancellationException(item.getId());
            }
        }

        long requestedAmount = selectedItems.stream().mapToLong(BookingItem::getUnitPrice).sum();
        RefundCalculationResult calculation = cancellationPolicyService.calculateRefund(booking, requestedAmount);
        long refundAmount = calculation.refundAmountInSmallestUnit();

        selectedItems.forEach(item -> cancellationRequestRepository.save(new CancellationRequest(
            booking.getId(),
            item.getId(),
            userId,
            reason
        )));

        if (selectedItems.size() == activeItems.size()) {
            booking.markCancellationPending();
        }

        Refund refund;
        try {
            refund = createRefundRecord(
                booking,
                reason,
                refundAmount,
                RefundCancellationType.BUYER_PARTIAL
            );
        } catch (StripeIntegrationException ex) {
            booking.revertCancellationPending();
            throw new RefundFailedException("Unable to process refund at this time. Please contact support.", ex.getMessage());
        }

        List<Long> cancelledSeatIds = cancelItemsAndVoidTickets(selectedItems);
        cancellationRequestRepository.findByBookingIdAndStatus(booking.getId(), CancellationRequestStatus.PENDING).stream()
            .filter(request -> request.getBookingItemId() != null)
            .filter(request -> selectedItems.stream().anyMatch(item -> item.getId().equals(request.getBookingItemId())))
            .forEach(CancellationRequest::approve);

        long remainingTotal = allItems.stream()
            .filter(item -> selectedItems.stream().noneMatch(cancelled -> cancelled.getId().equals(item.getId())))
            .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
            .mapToLong(BookingItem::getUnitPrice)
            .sum();
        booking.updateTotalAmount(remainingTotal);

        long activeRemaining = bookingItemRepository.findByBookingId(booking.getId()).stream()
            .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
            .count();
        if (activeRemaining == 0) {
            booking.cancelFromConfirmed();
        }

        publishBookingCancelled(booking, cancelledSeatIds, "BUYER_CANCEL");

        return new CancelItemsResponse(
            booking.getBookingRef(),
            booking.getStatus(),
            selectedItems.stream().map(BookingItem::getId).toList(),
            new CancelItemsResponse.ItemCancellationRefundResponse(
                refund.getCancellationType().name(),
                calculation.refundPercent(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus().name()
            )
        );
    }

    @Transactional
    public CancellationResponse cancel(Long userId, String bookingRef, RefundReason reason) {
        Booking booking = getOwnedBooking(userId, bookingRef);
        ensureBookingConfirmed(booking);

        List<BookingItem> activeItems = bookingItemRepository.findByBookingId(booking.getId()).stream()
            .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
            .toList();
        if (activeItems.isEmpty()) {
            throw new CancellationNotAllowedException("Booking has no active items to cancel");
        }

        cancellationRequestRepository.save(new CancellationRequest(booking.getId(), userId, reason));
        booking.markCancellationPending();

        RefundCalculationResult calculation = cancellationPolicyService.calculateRefund(booking, booking.getTotalAmount());

        Refund refund;
        try {
            refund = createRefundRecord(
                booking,
                reason,
                calculation.refundAmountInSmallestUnit(),
                RefundCancellationType.BUYER_FULL
            );
        } catch (StripeIntegrationException ex) {
            booking.revertCancellationPending();
            cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(booking.getId()).ifPresent(CancellationRequest::reject);
            throw new RefundFailedException("Unable to process refund at this time. Please contact support.", ex.getMessage());
        }

        List<Long> seatIds = cancelItemsAndVoidTickets(activeItems);
        booking.cancelFromConfirmed();
        booking.updateTotalAmount(0L);

        cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(booking.getId()).ifPresent(CancellationRequest::approve);
        publishBookingCancelled(booking, seatIds, "BUYER_CANCEL");

        return new CancellationResponse(
            booking.getBookingRef(),
            booking.getStatus(),
            new RefundResponse(refund.getStripeRefundId(), refund.getAmount(), refund.getCurrency(), refund.getStatus())
        );
    }

    @Transactional
    public Refund cancelForEventCancellation(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return refundRepository.findTopByBookingIdOrderByCreatedAtDesc(bookingId)
                .orElseGet(() -> refundRepository.save(new Refund(
                    bookingId,
                    null,
                    0L,
                    booking.getCurrency(),
                    RefundReason.REQUESTED_BY_CUSTOMER,
                    RefundStatus.SUCCEEDED,
                    RefundCancellationType.EVENT_CANCELLED
                )));
        }

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            booking.markCancellationPending();
        }

        Refund refund = createLocalSucceededRefund(
            booking,
            booking.getTotalAmount(),
            RefundReason.REQUESTED_BY_CUSTOMER,
            RefundCancellationType.EVENT_CANCELLED
        );

        List<BookingItem> activeItems = bookingItemRepository.findByBookingId(bookingId).stream()
            .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
            .toList();
        List<Long> seatIds = cancelItemsAndVoidTickets(activeItems);

        booking.cancelFromConfirmed();
        booking.updateTotalAmount(0L);

        publishBookingCancelled(booking, seatIds, "EVENT_CANCELLED");
        return refund;
    }

    @Transactional
    public void finaliseAfterRefund(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }

        Refund refund = refundRepository.findTopByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (refund == null) {
            return;
        }
        refund.updateStatus(RefundStatus.SUCCEEDED);

        if (refund.getCancellationType() == RefundCancellationType.BUYER_FULL || refund.getCancellationType() == RefundCancellationType.EVENT_CANCELLED) {
            List<BookingItem> activeItems = bookingItemRepository.findByBookingId(bookingId).stream()
                .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
                .toList();
            List<Long> seatIds = cancelItemsAndVoidTickets(activeItems);
            booking.cancelFromConfirmed();
            booking.updateTotalAmount(0L);
            cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(bookingId).ifPresent(CancellationRequest::approve);
            publishBookingCancelled(booking, seatIds, "BUYER_CANCEL");
        }
    }

    @Transactional
    public void handleRefundFailed(String refundId) {
        refundRepository.findByStripeRefundId(refundId).ifPresent(refund -> {
            refund.updateStatus(RefundStatus.FAILED);
            if (refund.getCancellationType() == RefundCancellationType.BUYER_FULL) {
                Booking booking = bookingRepository.findById(refund.getBookingId()).orElse(null);
                if (booking != null) {
                    booking.revertCancellationPending();
                }
                cancellationRequestRepository.findTopByBookingIdOrderByRequestedAtDesc(refund.getBookingId())
                    .ifPresent(CancellationRequest::reject);
            }
        });
    }

    private Booking getOwnedBooking(Long userId, String bookingRef) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new BookingNotFoundException(bookingRef));
        if (!Objects.equals(booking.getUserId(), userId)) {
            throw new BookingNotFoundException(bookingRef);
        }
        return booking;
    }

    private void ensureBookingConfirmed(Booking booking) {
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new CancellationNotAllowedException("Booking is not in CONFIRMED state", Map.of("currentStatus", booking.getStatus().name()));
        }
    }

    private List<BookingItem> resolveSelectedActiveItems(Long bookingId, List<Long> requestedBookingItemIds) {
        List<BookingItem> activeItems = bookingItemRepository.findByBookingId(bookingId).stream()
            .filter(item -> item.getStatus() == BookingItemStatus.ACTIVE)
            .toList();
        return resolveSelectedItemsFromActive(activeItems, requestedBookingItemIds);
    }

    private List<BookingItem> resolveSelectedItemsFromActive(List<BookingItem> activeItems, List<Long> requestedBookingItemIds) {
        if (requestedBookingItemIds == null || requestedBookingItemIds.isEmpty()) {
            throw new InvalidCancelItemsRequestException("bookingItemIds must not be empty");
        }

        Set<Long> dedupedIds = new LinkedHashSet<>(requestedBookingItemIds);
        Map<Long, BookingItem> activeById = new HashMap<>();
        for (BookingItem item : activeItems) {
            activeById.put(item.getId(), item);
        }

        List<BookingItem> selectedItems = dedupedIds.stream()
            .map(activeById::get)
            .filter(Objects::nonNull)
            .toList();

        if (selectedItems.size() != dedupedIds.size()) {
            throw new InvalidCancelItemsRequestException("All bookingItemIds must belong to the booking and be ACTIVE");
        }
        return selectedItems;
    }

    private Refund createRefundRecord(
        Booking booking,
        RefundReason reason,
        long refundAmount,
        RefundCancellationType cancellationType
    ) {
        if (refundAmount <= 0) {
            return createLocalSucceededRefund(booking, 0L, reason, cancellationType);
        }

        StripeRefundResponse stripeRefund = stripeRefundService.createRefund(new StripeRefundRequest(
            booking.getStripePaymentIntentId(),
            refundAmount,
            reason.name().toLowerCase(),
            "refund-" + booking.getBookingRef()
        ));

        RefundStatus refundStatus = toRefundStatus(stripeRefund.status());
        if (refundStatus == RefundStatus.FAILED) {
            throw new RefundFailedException("Unable to process refund at this time. Please contact support.", stripeRefund.status());
        }

        return refundRepository.save(new Refund(
            booking.getId(),
            stripeRefund.refundId(),
            refundAmount,
            booking.getCurrency(),
            reason,
            refundStatus,
            cancellationType
        ));
    }

    private Refund createLocalSucceededRefund(
        Booking booking,
        long refundAmount,
        RefundReason reason,
        RefundCancellationType cancellationType
    ) {
        return refundRepository.save(new Refund(
            booking.getId(),
            null,
            refundAmount,
            booking.getCurrency(),
            reason,
            RefundStatus.SUCCEEDED,
            cancellationType
        ));
    }

    private List<Long> cancelItemsAndVoidTickets(List<BookingItem> items) {
        Set<Long> seatIds = new LinkedHashSet<>();
        for (BookingItem item : items) {
            item.cancel();
            if (item.getSeatId() != null) {
                seatIds.add(item.getSeatId());
            }
            eTicketRepository.findByBookingItemId(item.getId()).ifPresent(ETicket::voidTicket);
        }
        return List.copyOf(seatIds);
    }

    private void publishBookingCancelled(Booking booking, List<Long> seatIds, String reason) {
        afterCommitEventPublisher.publish(new BookingCancelledEvent(
            booking.getId(),
            booking.getCartId(),
            seatIds,
            booking.getUserId(),
            reason
        ));
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
}

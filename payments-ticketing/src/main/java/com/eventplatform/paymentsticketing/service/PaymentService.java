package com.eventplatform.paymentsticketing.service;

import com.eventplatform.paymentsticketing.api.dto.response.BookingItemResponse;
import com.eventplatform.paymentsticketing.api.dto.response.BookingResponse;
import com.eventplatform.paymentsticketing.api.dto.response.CheckoutInitResponse;
import com.eventplatform.paymentsticketing.api.dto.response.ETicketResponse;
import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.BookingItem;
import com.eventplatform.paymentsticketing.domain.ETicket;
import com.eventplatform.paymentsticketing.domain.Payment;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import com.eventplatform.paymentsticketing.domain.enums.PaymentStatus;
import com.eventplatform.paymentsticketing.exception.BookingNotFoundException;
import com.eventplatform.paymentsticketing.exception.CartItemsFetchException;
import com.eventplatform.paymentsticketing.exception.PaymentIntentNotFoundException;
import com.eventplatform.paymentsticketing.exception.PaymentNotConfirmedException;
import com.eventplatform.paymentsticketing.mapper.BookingMapper;
import com.eventplatform.paymentsticketing.mapper.ETicketMapper;
import com.eventplatform.paymentsticketing.repository.BookingItemRepository;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.ETicketRepository;
import com.eventplatform.paymentsticketing.repository.PaymentRepository;
import com.eventplatform.shared.common.dto.CartItemSnapshotDto;
import com.eventplatform.shared.common.event.published.BookingConfirmedEvent;
import com.eventplatform.shared.common.event.published.CartAssembledEvent;
import com.eventplatform.shared.common.event.published.PaymentFailedEvent;
import com.eventplatform.shared.common.service.CartSnapshotReader;
import com.eventplatform.shared.common.service.SlotSummaryReader;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentRequest;
import com.eventplatform.shared.stripe.dto.StripePaymentIntentResponse;
import com.eventplatform.shared.stripe.service.StripePaymentService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final BookingItemRepository bookingItemRepository;
    private final ETicketRepository eTicketRepository;
    private final StripePaymentService stripePaymentService;
    private final BookingMapper bookingMapper;
    private final ETicketMapper eTicketMapper;
    private final AfterCommitEventPublisher afterCommitEventPublisher;
    private final BookingRefGenerator bookingRefGenerator;
    private final SlotSummaryReader slotSummaryReader;
    private final CartSnapshotReader cartSnapshotReader;

    public PaymentService(
        BookingRepository bookingRepository,
        PaymentRepository paymentRepository,
        BookingItemRepository bookingItemRepository,
        ETicketRepository eTicketRepository,
        StripePaymentService stripePaymentService,
        BookingMapper bookingMapper,
        ETicketMapper eTicketMapper,
        AfterCommitEventPublisher afterCommitEventPublisher,
        BookingRefGenerator bookingRefGenerator,
        SlotSummaryReader slotSummaryReader,
        CartSnapshotReader cartSnapshotReader
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.bookingItemRepository = bookingItemRepository;
        this.eTicketRepository = eTicketRepository;
        this.stripePaymentService = stripePaymentService;
        this.bookingMapper = bookingMapper;
        this.eTicketMapper = eTicketMapper;
        this.afterCommitEventPublisher = afterCommitEventPublisher;
        this.bookingRefGenerator = bookingRefGenerator;
        this.slotSummaryReader = slotSummaryReader;
        this.cartSnapshotReader = cartSnapshotReader;
    }

    @Transactional
    public CheckoutInitResponse createCheckout(CartAssembledEvent event) {
        Booking existingBooking = bookingRepository.findByCartId(event.cartId()).orElse(null);
        if (existingBooking != null) {
            Payment existingPayment = paymentRepository.findByBookingId(existingBooking.getId()).orElse(null);
            if (existingPayment != null && (existingPayment.getStatus() == PaymentStatus.PENDING || existingPayment.getStatus() == PaymentStatus.SUCCESS)) {
                StripePaymentIntentResponse paymentIntent = stripePaymentService.getPaymentIntent(existingPayment.getStripePaymentIntentId());
                return new CheckoutInitResponse(
                    event.cartId(),
                    existingBooking.getBookingRef(),
                    existingPayment.getStripePaymentIntentId(),
                    paymentIntent.clientSecret(),
                    existingPayment.getAmount(),
                    existingPayment.getCurrency()
                );
            }
        }

        String bookingRef = bookingRefGenerator.nextRef();
        Long eventId = event.slotId();
        if (slotSummaryReader.getSlotSummary(event.slotId()) == null) {
            throw new CartItemsFetchException("Unable to resolve slot details for cart=" + event.cartId());
        }

        Booking booking = bookingRepository.save(new Booking(
            bookingRef,
            event.cartId(),
            event.userId(),
            eventId,
            event.slotId(),
            event.totalAmountInSmallestUnit(),
            event.currency()
        ));

        StripePaymentIntentRequest request = new StripePaymentIntentRequest(
            event.totalAmountInSmallestUnit(),
            event.currency(),
            event.userEmail(),
            "Booking " + bookingRef,
            "cart-" + event.cartId(),
            Map.of(
                "booking_ref", bookingRef,
                "user_id", String.valueOf(event.userId())
            )
        );
        StripePaymentIntentResponse paymentIntent = stripePaymentService.createPaymentIntent(request);

        Payment payment = new Payment(
            booking.getId(),
            paymentIntent.paymentIntentId(),
            event.totalAmountInSmallestUnit(),
            event.currency()
        );
        paymentRepository.save(payment);

        return new CheckoutInitResponse(
            event.cartId(),
            bookingRef,
            paymentIntent.paymentIntentId(),
            paymentIntent.clientSecret(),
            event.totalAmountInSmallestUnit(),
            event.currency()
        );
    }

    @Transactional(readOnly = true)
    public CheckoutInitResponse getCheckout(Long cartId, Long userId) {
        Booking booking = bookingRepository.findByCartId(cartId).orElseThrow(() -> new BookingNotFoundException("cart-" + cartId));
        if (!Objects.equals(booking.getUserId(), userId)) {
            throw new BookingNotFoundException(booking.getBookingRef());
        }
        Payment payment = paymentRepository.findByBookingId(booking.getId())
            .orElseThrow(() -> new PaymentIntentNotFoundException("cart-" + cartId));

        StripePaymentIntentResponse paymentIntent = stripePaymentService.getPaymentIntent(payment.getStripePaymentIntentId());
        return new CheckoutInitResponse(
            cartId,
            booking.getBookingRef(),
            payment.getStripePaymentIntentId(),
            paymentIntent.clientSecret(),
            payment.getAmount(),
            payment.getCurrency()
        );
    }

    @Transactional(readOnly = true)
    public BookingResponse getBookingResponse(Long userId, String bookingRef) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
            .orElseThrow(() -> new BookingNotFoundException(bookingRef));
        if (!Objects.equals(booking.getUserId(), userId)) {
            throw new BookingNotFoundException(bookingRef);
        }
        return buildBookingResponse(booking);
    }

    @Transactional
    public BookingResponse confirmPayment(Long userId, String paymentIntentId) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId)
            .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
        Booking booking = bookingRepository.findById(payment.getBookingId())
            .orElseThrow(() -> new BookingNotFoundException(payment.getBookingId()));

        if (!Objects.equals(booking.getUserId(), userId)) {
            throw new BookingNotFoundException(booking.getBookingRef());
        }

        if (payment.getStatus() == PaymentStatus.SUCCESS && booking.getStatus() == BookingStatus.CONFIRMED) {
            return buildBookingResponse(booking);
        }

        StripePaymentIntentResponse stripePaymentIntent = stripePaymentService.getPaymentIntent(paymentIntentId);
        if (!"succeeded".equalsIgnoreCase(stripePaymentIntent.status())) {
            throw new PaymentNotConfirmedException(stripePaymentIntent.status());
        }

        payment.markSuccess(stripePaymentIntent.latestCharge());
        booking.confirm(paymentIntentId, stripePaymentIntent.latestCharge());

        List<CartItemSnapshotDto> cartItems = cartSnapshotReader.getCartItems(booking.getCartId());
        if (cartItems.isEmpty()) {
            throw new CartItemsFetchException("No cart items found for cart=" + booking.getCartId());
        }

        List<Long> seatIds = new ArrayList<>();
        for (CartItemSnapshotDto cartItem : cartItems) {
            int quantity = cartItem.quantity() == null || cartItem.quantity() < 1 ? 1 : cartItem.quantity();
            for (int i = 0; i < quantity; i++) {
                BookingItem item = bookingItemRepository.save(new BookingItem(
                    booking.getId(),
                    cartItem.seatId(),
                    cartItem.gaClaimId(),
                    cartItem.ticketClassId(),
                    cartItem.unitPrice(),
                    cartItem.currency()
                ));

                String rawQrData = booking.getBookingRef() + ":" + item.getId();
                String encodedQrData = Base64.getEncoder().encodeToString(rawQrData.getBytes(StandardCharsets.UTF_8));
                ETicket eTicket = new ETicket(
                    booking.getId(),
                    item.getId(),
                    encodedQrData,
                    "/tickets/" + booking.getBookingRef() + "/" + item.getId() + ".pdf"
                );
                eTicketRepository.save(eTicket);

                if (item.getSeatId() != null) {
                    seatIds.add(item.getSeatId());
                }
            }
        }

        afterCommitEventPublisher.publish(new BookingConfirmedEvent(
            booking.getCartId(),
            seatIds,
            paymentIntentId,
            booking.getUserId()
        ));

        return buildBookingResponse(booking);
    }

    @Transactional
    public BookingResponse confirmPaymentFromWebhook(String paymentIntentId) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId)
            .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
        Booking booking = bookingRepository.findById(payment.getBookingId())
            .orElseThrow(() -> new BookingNotFoundException(payment.getBookingId()));
        return confirmPayment(booking.getUserId(), paymentIntentId);
    }

    @Transactional
    public void handleFailure(String paymentIntentId, String failureCode, String failureMessage) {
        Payment payment = paymentRepository.findByStripePaymentIntentId(paymentIntentId)
            .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));

        if (payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }

        Booking booking = bookingRepository.findById(payment.getBookingId())
            .orElseThrow(() -> new BookingNotFoundException(payment.getBookingId()));

        payment.markFailed(failureCode, failureMessage);
        booking.cancelDueToPaymentFailure();

        List<Long> seatIds = bookingItemRepository.findByBookingId(booking.getId())
            .stream()
            .map(BookingItem::getSeatId)
            .filter(Objects::nonNull)
            .toList();

        afterCommitEventPublisher.publish(new PaymentFailedEvent(booking.getCartId(), seatIds, booking.getUserId()));
    }

    private BookingResponse buildBookingResponse(Booking booking) {
        BookingResponse response = bookingMapper.toResponse(booking);

        Map<Long, ETicket> ticketsByBookingItemId = eTicketRepository.findByBookingId(booking.getId()).stream()
            .collect(java.util.stream.Collectors.toMap(ETicket::getBookingItemId, ticket -> ticket, (a, b) -> a, HashMap::new));

        List<BookingItemResponse> items = bookingItemRepository.findByBookingId(booking.getId()).stream()
            .map(item -> {
                ETicket ticket = ticketsByBookingItemId.get(item.getId());
                ETicketResponse mappedTicket = ticket == null ? null : withTicketCode(eTicketMapper.toResponse(ticket), booking.getBookingRef(), item.getId());
                return new BookingItemResponse(
                    item.getId(),
                    item.getSeatId(),
                    item.getGaClaimId(),
                    item.getTicketClassId(),
                    item.getUnitPrice(),
                    item.getCurrency(),
                    item.getStatus(),
                    mappedTicket
                );
            })
            .toList();

        return new BookingResponse(
            response.bookingRef(),
            response.status(),
            response.slotId(),
            response.totalAmount(),
            response.currency(),
            response.stripePaymentIntentId(),
            items,
            response.createdAt()
        );
    }

    private ETicketResponse withTicketCode(ETicketResponse rawResponse, String bookingRef, Long bookingItemId) {
        return new ETicketResponse(
            bookingRef + ":" + bookingItemId,
            bookingItemId,
            rawResponse.qrCodeData(),
            rawResponse.pdfUrl(),
            rawResponse.status()
        );
    }

}

package com.eventplatform.paymentsticketing.api.internal;

import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.Payment;
import com.eventplatform.paymentsticketing.repository.BookingRepository;
import com.eventplatform.paymentsticketing.repository.PaymentRepository;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoints consumed only by other modules via REST (never called by
 * the external client).
 *
 * <p>Secured by the JWT filter (ROLE_SYSTEM or any authenticated user) — the
 * PaymentStatusClient in booking-inventory calls this without a token because
 * it runs as a server-side scheduled task. The endpoint is restricted to
 * internal network access via load balancer / Kubernetes NetworkPolicy.
 */
@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;

    public InternalPaymentController(BookingRepository bookingRepository,
                                     PaymentRepository paymentRepository) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Returns the payment status for a given cart ID.
     * Used by booking-inventory's PaymentTimeoutWatchdog.
     *
     * @param cartId the cart ID from booking-inventory.
     * @return {@code {"status":"SUCCEEDED"}} or {@code 404 Not Found} if no payment exists.
     */
    @GetMapping("/by-cart/{cartId}/status")
    public ResponseEntity<PaymentStatusDto> getPaymentStatusByCart(@PathVariable Long cartId) {
        Optional<Booking> booking = bookingRepository.findByCartId(cartId);
        if (booking.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<Payment> payment = paymentRepository.findByBookingId(booking.get().getId());
        if (payment.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new PaymentStatusDto(payment.get().getStatus().name()));
    }

    public record PaymentStatusDto(String status) {}
}

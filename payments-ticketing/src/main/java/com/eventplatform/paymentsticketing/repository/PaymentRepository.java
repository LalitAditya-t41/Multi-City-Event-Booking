package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

  Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

  Optional<Payment> findByBookingId(Long bookingId);
}

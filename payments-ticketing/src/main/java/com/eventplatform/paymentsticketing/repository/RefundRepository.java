package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.Refund;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByBookingId(Long bookingId);

    Optional<Refund> findByStripeRefundId(String stripeRefundId);
}

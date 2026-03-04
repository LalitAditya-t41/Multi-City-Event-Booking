package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.Booking;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingRef(String bookingRef);

    Optional<Booking> findByCartId(Long cartId);
}

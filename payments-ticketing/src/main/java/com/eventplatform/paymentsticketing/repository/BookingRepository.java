package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.Booking;
import com.eventplatform.paymentsticketing.domain.enums.BookingStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingRef(String bookingRef);

    Optional<Booking> findByCartId(Long cartId);

    boolean existsByCartIdAndStatus(Long cartId, BookingStatus status);

    Page<Booking> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<Booking> findBySlotIdAndStatus(Long slotId, BookingStatus status);

    Optional<Booking> findTopByUserIdAndEventIdOrderByCreatedAtDesc(Long userId, Long eventId);
}

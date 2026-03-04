package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.BookingItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingItemRepository extends JpaRepository<BookingItem, Long> {

    List<BookingItem> findByBookingId(Long bookingId);
}

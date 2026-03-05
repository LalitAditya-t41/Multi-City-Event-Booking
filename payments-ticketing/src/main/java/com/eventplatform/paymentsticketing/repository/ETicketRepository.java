package com.eventplatform.paymentsticketing.repository;

import com.eventplatform.paymentsticketing.domain.ETicket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ETicketRepository extends JpaRepository<ETicket, Long> {

  List<ETicket> findByBookingId(Long bookingId);

  Optional<ETicket> findByBookingItemId(Long bookingItemId);
}
